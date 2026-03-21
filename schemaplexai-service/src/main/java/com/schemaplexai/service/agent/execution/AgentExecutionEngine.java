package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelResolution;
import com.schemaplexai.service.ai.ChatMessage;
import com.schemaplexai.service.ai.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 执行引擎 — Agentic Loop 实现
 *
 * <p>执行流程（最多 {@link #MAX_ROUNDS} 轮）：
 * <ol>
 *   <li>构建四层 System Prompt（{@link ContextInjector}）</li>
 *   <li>初始化对话历史</li>
 *   <li>解析 AI 模型配置（{@link AIModelRouter}）</li>
 *   <li>Agentic Loop：调用 AI → 判断停止条件 → tool_use 时执行工具并 continue</li>
 * </ol>
 *
 * <p>方法职责分层：
 * <ul>
 *   <li>{@link #execute}       — 异步入口：校验 + try/catch 边界</li>
 *   <li>{@link #doExecute}     — 编排：构建 prompt / history / resolution → 进入 loop</li>
 *   <li>{@link #runAgenticLoop} — Agentic Loop 主体</li>
 *   <li>{@link #callAI}        — 单轮 AI 调用 + 轮次日志</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private static final int    MAX_ROUNDS       = 50;
    private static final int    LOG_CONTENT_LIMIT = 500;

    private static final String STATUS_RUNNING  = "running";
    private static final String STATUS_STOPPED  = "stopped";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED   = "failed";

    private final AgentLogService      agentLogService;
    private final ContextInjector      contextInjector;
    private final AIModelRouter        aiModelRouter;
    private final AgentExecutionMapper agentExecutionMapper;
    private final ObjectMapper         objectMapper;

    // =========================================================================
    //  公开入口
    // =========================================================================

    /**
     * 异步执行 Agent 任务（在 agentExecutorPool 线程池中运行）
     */
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> execute(AgentExecutionContext ctx) {
        if (ctx == null || !StringUtils.hasText(ctx.getExecutionId()) || !StringUtils.hasText(ctx.getAgentId())) {
            log.error("Agent 执行上下文非法: {}", ctx);
            return done(AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage("执行上下文缺失").build());
        }

        long startMs = System.currentTimeMillis();
        agentLogService.updateExecutionStatus(ctx.getExecutionId(), STATUS_RUNNING, null, null, null, null);
        log.info("Agent 执行开始: executionId={}, agentId={}, model={}", ctx.getExecutionId(), ctx.getAgentId(), ctx.getModel());

        try {
            return done(doExecute(ctx, startMs));
        } catch (Exception e) {
            log.error("Agent 执行异常: executionId={}", ctx.getExecutionId(), e);
            return done(handleFatalError(ctx, e, startMs));
        }
    }

    // =========================================================================
    //  编排层
    // =========================================================================

    /** 构建执行环境并启动 Agentic Loop */
    private AgentExecutionResult doExecute(AgentExecutionContext ctx, long startMs) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();

        // 1. 构建 System Prompt
        String extraContext = buildExtraContext(ctx.getInputPrompt(), ctx.getInputContext());
        String systemPrompt = contextInjector.buildSystemPrompt(agentId, extraContext, tenantId, null);
        appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_START", 0,
                "System Prompt 构建完成（" + systemPrompt.length() + " chars），model=" + ctx.getModel(), startMs);

        // 2. 初始化对话历史
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user(buildUserMessage(ctx.getInputPrompt(), ctx.getInputContext())));

        // 3. 解析 AI 模型（从 sf_ai_model 表动态加载）
        AiModelResolution resolution = aiModelRouter.resolveByModelName(ctx.getModel());
        appendLog(executionId, agentId, tenantId, "DEBUG", "MODEL_RESOLVED", 0,
                "provider=" + resolution.config().getProvider() + ", modelId=" + resolution.config().getModelId(), startMs);

        // 4. Agentic Loop
        return runAgenticLoop(ctx, systemPrompt, history, resolution, startMs);
    }

    // =========================================================================
    //  Agentic Loop
    // =========================================================================

    /**
     * Agentic Loop 主体。
     *
     * <p>退出方式：
     * <ul>
     *   <li>{@code return} — 异常退出：stopped / AI 调用失败（不纳入正常轮次计数）</li>
     *   <li>{@code break}  — 正常退出：end_turn / stop / max_tokens；
     *                        或 tool_use 但 MCP 未集成时的临时降级</li>
     *   <li>循环自然结束   — 超出最大轮次，视为失败</li>
     * </ul>
     * <p>MCP 集成后，{@code tool_use} 路径执行工具并 {@code continue} 进入下一轮。
     */
    private AgentExecutionResult runAgenticLoop(AgentExecutionContext ctx,
            String systemPrompt, List<ChatMessage> history, AiModelResolution resolution, long startMs) {

        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();

        long    totalTokenInput  = 0;
        long    totalTokenOutput = 0;
        String  lastContent      = "";
        int     lastRound        = 0;
        boolean loopCompleted    = false; // true = break 正常退出；false = 轮次耗尽

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            lastRound = round;

            // ── 停止检查 ──────────────────────────────────────────────────────
            if (isStopped(executionId)) {
                appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_STOPPED", round,
                        "执行已被用户停止", startMs);
                log.info("Agent 执行被停止: executionId={}, round={}", executionId, round);
                return AgentExecutionResult.builder().status(STATUS_STOPPED).rounds(round).build();
            }

            // ── 调用 AI ───────────────────────────────────────────────────────
            ChatResponse response = callAI(ctx, round, systemPrompt, history, resolution, startMs);

            if (!response.isSuccess()) {
                String errMsg = response.getErrorMessage();
                log.error("AI 调用失败: executionId={}, round={}, error={}", executionId, round, errMsg);
                agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                        totalTokenInput, totalTokenOutput, null);
                return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).rounds(round).build();
            }

            // ── 累计 token，记录响应日志 ──────────────────────────────────────
            totalTokenInput  += response.getInputTokens();
            totalTokenOutput += response.getOutputTokens();
            lastContent       = response.getContent();
            logAIResponse(executionId, agentId, tenantId, round, response, startMs);

            // ── 判断下一步行为 ─────────────────────────────────────────────────
            if (!"tool_use".equals(response.getStopReason())) {
                // end_turn / stop / max_tokens → 正常完成，退出循环
                loopCompleted = true;
                break;
            }

             // tool_use：执行工具，将结果追加到 history，然后 continue 进入下一轮
//            List<ToolResult> results = toolRegistry.executeAll(response.getToolUses());
//                   history.add(ChatMessage.assistant(lastContent));
//                   history.add(ChatMessage.toolResults(results));
//                   continue;

            // MCP 未集成：记录警告后退出循环（集成后删除此 break）
            log.warn("AI 返回 tool_use，MCP Tool Registry 尚未集成: executionId={}, round={}", executionId, round);
            appendLog(executionId, agentId, tenantId, "WARN", "TOOL_USE_UNSUPPORTED", round,
                    "AI 请求工具调用，MCP 未集成，本轮视为完成", startMs);
            loopCompleted = true;
            break;
        }

        // ── 超出最大轮次（循环自然结束，未触发 break）────────────────────────
        if (!loopCompleted) {
            String errMsg = "超出最大执行轮次 " + MAX_ROUNDS;
            log.warn("Agent 超出最大轮次: executionId={}", executionId);
            appendLog(executionId, agentId, tenantId, "ERROR", "EXECUTION_FAILED", MAX_ROUNDS, errMsg, startMs);
            agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                    totalTokenInput, totalTokenOutput, null);
            return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
        }

        return buildCompletedResult(executionId, agentId, tenantId,
                lastContent, totalTokenInput, totalTokenOutput, lastRound, startMs);
    }

    // =========================================================================
    //  单轮 AI 调用
    // =========================================================================

    /** 执行单轮 AI 调用，并记录轮次开始日志 */
    private ChatResponse callAI(AgentExecutionContext ctx, int round, String systemPrompt,
            List<ChatMessage> history, AiModelResolution resolution, long startMs) {
        appendLog(ctx.getExecutionId(), ctx.getAgentId(), ctx.getTenantId(),
                "INFO", "ROUND_START", round,
                "第 " + round + " 轮开始，history=" + history.size() + " msgs", startMs);
        return resolution.provider().chatWithHistory(systemPrompt, history, resolution.config());
    }

    /** 记录 AI 响应摘要日志 */
    private void logAIResponse(String executionId, String agentId, String tenantId,
            int round, ChatResponse response, long startMs) {
        String content = response.getContent();
        String preview = content.length() > LOG_CONTENT_LIMIT
                ? content.substring(0, LOG_CONTENT_LIMIT) + "...[截断]"
                : content;
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO", "AI_RESPONSE",
                round, null, preview, (int) response.getOutputTokens(), elapsed(startMs));
        log.info("第 {} 轮 AI 响应: executionId={}, inputTokens={}, outputTokens={}, stopReason={}",
                round, executionId, response.getInputTokens(), response.getOutputTokens(), response.getStopReason());
    }

    // =========================================================================
    //  结果构建
    // =========================================================================

    private AgentExecutionResult buildCompletedResult(String executionId, String agentId, String tenantId,
            String content, long tokenInput, long tokenOutput, int rounds, long startMs) {
        agentLogService.updateExecutionStatus(executionId, STATUS_COMPLETED, null, tokenInput, tokenOutput, content);
        appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_COMPLETED", rounds,
                "执行完成，轮次=" + rounds + " inputTokens=" + tokenInput + " outputTokens=" + tokenOutput, startMs);
        log.info("Agent 执行完成: executionId={}, rounds={}, totalTokens={}", executionId, rounds, tokenInput + tokenOutput);
        return AgentExecutionResult.builder()
                .status(STATUS_COMPLETED)
                .outputResult(content)
                .tokenInput(tokenInput)
                .tokenOutput(tokenOutput)
                .rounds(rounds)
                .build();
    }

    private AgentExecutionResult handleFatalError(AgentExecutionContext ctx, Exception e, long startMs) {
        String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        agentLogService.updateExecutionStatus(ctx.getExecutionId(), STATUS_FAILED, errMsg, null, null, null);
        appendLog(ctx.getExecutionId(), ctx.getAgentId(), ctx.getTenantId(), "ERROR", "EXECUTION_FAILED",
                0, "执行异常: " + errMsg, startMs);
        return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    private String buildExtraContext(String inputPrompt, Map<String, Object> inputContext) {
        if (inputContext == null || inputContext.isEmpty()) return inputPrompt;
        try {
            String contextJson = objectMapper.writeValueAsString(inputContext);
            return StringUtils.hasText(inputPrompt)
                    ? inputPrompt + "\n\n附加上下文:\n" + contextJson
                    : "附加上下文:\n" + contextJson;
        } catch (Exception e) {
            return inputPrompt;
        }
    }

    private String buildUserMessage(String inputPrompt, Map<String, Object> inputContext) {
        if (inputContext == null || inputContext.isEmpty()) return inputPrompt;
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(inputPrompt)) sb.append(inputPrompt).append("\n\n");
        sb.append("---\n以下为补充上下文变量：\n");
        inputContext.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        return sb.toString().trim();
    }

    private boolean isStopped(String executionId) {
        AgentExecution e = agentExecutionMapper.selectById(executionId);
        return e != null && STATUS_STOPPED.equals(e.getStatus());
    }

    /** 简化版 appendLog，省略 tokenCount 和 toolCallId 参数 */
    private void appendLog(String executionId, String agentId, String tenantId,
            String level, String event, int round, String message, long startMs) {
        agentLogService.appendLog(executionId, agentId, tenantId, level, event,
                round, null, message, null, elapsed(startMs));
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private static CompletableFuture<AgentExecutionResult> done(AgentExecutionResult r) {
        return CompletableFuture.completedFuture(r);
    }
}
