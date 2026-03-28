package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.service.agent.tool.ToolRegistry;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 执行引擎 — Agentic Loop 实现（基于 LangChain4j）
 *
 * <p>执行流程（最多 {@link #MAX_ROUNDS} 轮）：
 * <ol>
 *   <li>构建四层 System Prompt（{@link ContextInjector}）</li>
 *   <li>初始化对话历史</li>
 *   <li>解析 AI 模型配置（{@link AIModelRouter}）</li>
 *   <li>Agentic Loop：调用 AI → 有工具调用请求时执行工具并 continue → 无工具请求时退出</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private static final int    MAX_ROUNDS        = 50;
    private static final int    LOG_CONTENT_LIMIT = 500;

    private static final String STATUS_RUNNING   = AgentExecutionStatusEnum.RUNNING.getCode();
    private static final String STATUS_STOPPED   = AgentExecutionStatusEnum.STOPPED.getCode();
    private static final String STATUS_COMPLETED = AgentExecutionStatusEnum.COMPLETED.getCode();
    private static final String STATUS_FAILED    = AgentExecutionStatusEnum.FAILED.getCode();

    private final AgentLogService             agentLogService;
    private final ContextInjector             contextInjector;
    private final AIModelRouter               aiModelRouter;
    private final AgentExecutionMapper        agentExecutionMapper;
    private final ObjectMapper                objectMapper;
    private final ExecutionEventStreamService executionEventStreamService;
    private final ToolRegistry                toolRegistry;
    private final LangChain4jToolSpecProvider toolSpecProvider;
    private final CompositeChatMemoryStore compositeChatMemoryStore;

    // =========================================================================
    //  公开入口
    // =========================================================================

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

    private AgentExecutionResult doExecute(AgentExecutionContext ctx, long startMs) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();

        String extraContext = buildExtraContext(ctx.getInputPrompt(), ctx.getInputContext());
        String systemPrompt = contextInjector.buildSystemPrompt(agentId, extraContext, tenantId, null);
        appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_START", 0,
                "System Prompt 构建完成（" + systemPrompt.length() + " chars），model=" + ctx.getModel(), startMs);
        publishEvent(executionId, "CONTEXT_INJECT", 0, "正在注入上下文", null, startMs);

        // 解析 conversationId（前端传入或自动生成），并持久化到执行记录
        String conversationId = resolveConversationId(ctx);
        persistConversationId(executionId, conversationId);

        // 构建持久化 ChatMemory（Redis L1 + PostgreSQL L2）
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .id(conversationId)
                .maxMessages(ctx.getMaxMessages())
                .chatMemoryStore(compositeChatMemoryStore)
                .build();

        // 追加本轮用户消息
        chatMemory.add(UserMessage.from(buildUserMessage(ctx.getInputPrompt(), ctx.getInputContext())));

        log.info("ChatMemory 初始化完成: conversationId={}, 历史消息数={}", conversationId, chatMemory.messages().size());

        LangChain4jResolution resolution;
        if ("model_group".equals(ctx.getAgentModelType()) && StringUtils.hasText(ctx.getAgentModelGroupId())) {
            resolution = aiModelRouter.resolveFromGroup(ctx.getAgentModelGroupId());
        } else {
            resolution = aiModelRouter.resolveByModelName(ctx.getModel());
        }
        appendLog(executionId, agentId, tenantId, "DEBUG", "MODEL_RESOLVED", 0,
                "provider=" + resolution.config().getProvider() + ", modelId=" + resolution.config().getModelId(), startMs);

        return runAgenticLoop(ctx, systemPrompt, chatMemory, resolution, startMs, conversationId);
    }

    // =========================================================================
    //  Agentic Loop
    // =========================================================================

    private AgentExecutionResult runAgenticLoop(AgentExecutionContext ctx,
            String systemPrompt, ChatMemory chatMemory, LangChain4jResolution resolution, long startMs, String conversationId) {

        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();

        List<ToolDefinition>   enabledTools = toolRegistry.listEnabledTools(tenantId, agentId);
        List<ToolSpecification> toolSpecs   = toolSpecProvider.toToolSpecifications(enabledTools);

        long    totalTokenInput  = 0;
        long    totalTokenOutput = 0;
        String  lastContent      = "";
        int     lastRound        = 0;
        boolean loopCompleted    = false;

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            lastRound = round;

            if (isStopped(executionId)) {
                appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_STOPPED", round, "执行已被用户停止", startMs);
                publishEvent(executionId, "CANCELLED", round, "执行已被用户停止", null, startMs);
                log.info("Agent 执行被停止: executionId={}, round={}", executionId, round);
                return AgentExecutionResult.builder().status(STATUS_STOPPED).rounds(round).build();
            }

            List<ChatMessage> memoryMessages = chatMemory.messages();
            appendLog(executionId, agentId, tenantId, "INFO", "ROUND_START", round,
                    "第 " + round + " 轮开始，history=" + memoryMessages.size() + " msgs", startMs);
            publishEvent(executionId, "ROUND_START", round, "第 " + round + " 轮推理开始", null, startMs);

            // 构建请求：SystemMessage 固定首位（不存入 ChatMemory），后跟对话历史
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(systemPrompt));
            messages.addAll(memoryMessages);

            ChatRequest request = ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(toolSpecs)
                    .build();

            // 单轮 AI 调用
            ChatResponse response;
            try {
                response = resolution.model().chat(request);
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("AI 调用失败: executionId={}, round={}, error={}", executionId, round, errMsg);
                agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                        totalTokenInput, totalTokenOutput, null);
                publishEvent(executionId, "FAILED", round, "AI 调用失败: " + errMsg, null, startMs);
                return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).rounds(round).build();
            }

            // 累计 token
            var tokenUsage = response.metadata().tokenUsage();
            if (tokenUsage != null) {
                totalTokenInput  += tokenUsage.inputTokenCount();
                totalTokenOutput += tokenUsage.outputTokenCount();
            }

            AiMessage aiMsg = response.aiMessage();
            lastContent = aiMsg.text() != null ? aiMsg.text() : "";

            // 记录 AI 响应摘要
            String preview = lastContent.length() > LOG_CONTENT_LIMIT
                    ? lastContent.substring(0, LOG_CONTENT_LIMIT) + "...[截断]"
                    : lastContent;
            agentLogService.appendLog(executionId, agentId, tenantId, "INFO", "AI_RESPONSE",
                    round, null, preview, tokenUsage != null ? (int) tokenUsage.outputTokenCount() : 0, elapsed(startMs));
            String finishReason = response.metadata().finishReason() != null
                    ? response.metadata().finishReason().toString() : "STOP";
            publishEvent(executionId, "AI_RESPONSE", round,
                    "已收到模型响应（finishReason=" + finishReason + "）", null, startMs);
            log.info("第 {} 轮 AI 响应: executionId={}, inputTokens={}, outputTokens={}, finishReason={}",
                    round, executionId,
                    tokenUsage != null ? tokenUsage.inputTokenCount() : 0,
                    tokenUsage != null ? tokenUsage.outputTokenCount() : 0,
                    finishReason);

            // 无工具调用 → 检查 finishReason 判断是否真正完成
            if (!aiMsg.hasToolExecutionRequests()) {
                String reason = response.metadata().finishReason() != null
                        ? response.metadata().finishReason().toString().toUpperCase() : "";
                if ("LENGTH".equals(reason) || "MAX_OUTPUT_TOKENS".equals(reason)) {
                    // 输出被 maxTokens 截断，追加截断提示并继续下一轮
                    appendLog(executionId, agentId, tenantId, "WARN", "OUTPUT_TRUNCATED",
                            round, "模型输出被 maxTokens 截断，追加继续提示并继续执行", startMs);
                    chatMemory.add(aiMsg);
                    chatMemory.add(UserMessage.from(
                            "请继续完成上一条消息中被截断的内容，直接输出完整内容，不需要解释。"));
                    continue;
                }
                // 正常结束，将最终 AI 回复存入记忆
                chatMemory.add(aiMsg);
                loopCompleted = true;
                break;
            }

            // 执行工具调用
            List<ToolExecutionRequest> toolExecRequests = aiMsg.toolExecutionRequests();
            if (toolExecRequests == null || toolExecRequests.isEmpty()) {
                chatMemory.add(aiMsg);
                loopCompleted = true;
                break;
            }
            List<ToolCall> toolCalls = toolExecRequests.stream()
                    .map(req -> ToolCall.builder()
                            .callId(req.id())
                            .toolCode(req.name())
                            .arguments(parseJsonOrEmpty(req.arguments()))
                            .build())
                    .toList();

            publishEvent(executionId, "TOOL_CALL", round, "开始执行工具调用(" + toolCalls.size() + "个)", null, startMs);
            List<ToolResult> toolResults = toolRegistry.executeAll(tenantId, agentId, toolCalls);

            // 追加 AI 消息（含工具调用请求）和工具执行结果到 ChatMemory
            chatMemory.add(aiMsg);
            for (int i = 0; i < toolExecRequests.size(); i++) {
                ToolExecutionRequest req = toolExecRequests.get(i);
                ToolResult result = i < toolResults.size() ? toolResults.get(i) : null;
                chatMemory.add(ToolExecutionResultMessage.from(req.id(), req.name(), serializeResult(result)));
            }

            publishEvent(executionId, "TOOL_RESULT", round, "工具调用完成(" + toolResults.size() + "个)", null, startMs);
            appendLog(executionId, agentId, tenantId, "INFO", "TOOL_RESULT", round,
                    "工具调用完成，数量=" + toolResults.size(), startMs);
        }

        if (!loopCompleted) {
            String errMsg = "超出最大执行轮次 " + MAX_ROUNDS;
            log.warn("Agent 超出最大轮次: executionId={}", executionId);
            appendLog(executionId, agentId, tenantId, "ERROR", "EXECUTION_FAILED", MAX_ROUNDS, errMsg, startMs);
            agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                    totalTokenInput, totalTokenOutput, null);
            publishEvent(executionId, "FAILED", MAX_ROUNDS, errMsg, null, startMs);
            return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
        }

        return buildCompletedResult(executionId, agentId, tenantId,
                lastContent, totalTokenInput, totalTokenOutput, lastRound, startMs, conversationId);
    }

    // =========================================================================
    //  结果构建
    // =========================================================================

    private AgentExecutionResult buildCompletedResult(String executionId, String agentId, String tenantId,
            String content, long tokenInput, long tokenOutput, int rounds, long startMs, String conversationId) {
        agentLogService.updateExecutionStatus(executionId, STATUS_COMPLETED, null, tokenInput, tokenOutput, content);
        appendLog(executionId, agentId, tenantId, "INFO", "EXECUTION_COMPLETED", rounds,
                "执行完成，轮次=" + rounds + " inputTokens=" + tokenInput + " outputTokens=" + tokenOutput, startMs);
        Map<String, Object> payload = new HashMap<>();
        payload.put("finalOutput", content);
        payload.put("tokenInput", tokenInput);
        payload.put("tokenOutput", tokenOutput);
        publishEvent(executionId, "COMPLETED", rounds, "执行完成", payload, startMs);
        log.info("Agent 执行完成: executionId={}, rounds={}, totalTokens={}", executionId, rounds, tokenInput + tokenOutput);
        return AgentExecutionResult.builder()
                .status(STATUS_COMPLETED)
                .outputResult(content)
                .conversationId(conversationId)
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
        publishEvent(ctx.getExecutionId(), "FAILED", 0, "执行异常: " + errMsg, null, startMs);
        return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /**
     * 解析 conversationId：优先使用前端传入的值，否则自动生成
     */
    private String resolveConversationId(AgentExecutionContext ctx) {
        if (StringUtils.hasText(ctx.getConversationId())) {
            return ctx.getConversationId();
        }
        String generated = UUID.randomUUID().toString().replace("-", "");
        ctx.setConversationId(generated);
        log.info("自动生成 conversationId: {}", generated);
        return generated;
    }

    /**
     * 将 conversationId 持久化到执行记录
     */
    private void persistConversationId(String executionId, String conversationId) {
        try {
            AgentExecution execution = agentExecutionMapper.selectById(executionId);
            if (execution != null) {
                execution.setConversationId(conversationId);
                agentExecutionMapper.updateById(execution);
            }
        } catch (Exception e) {
            log.warn("持久化 conversationId 失败: executionId={}, error={}", executionId, e.getMessage());
        }
    }

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

    private JsonNode parseJsonOrEmpty(String jsonStr) {
        if (!StringUtils.hasText(jsonStr)) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(jsonStr);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String serializeResult(ToolResult result) {
        if (result == null) return "{}";
        try {
            if (result.isSuccess()) {
                return result.getResult() != null ? objectMapper.writeValueAsString(result.getResult()) : "{}";
            } else {
                String msg = result.getErrorMessage() != null ? result.getErrorMessage().replace("\"", "'") : "unknown error";
                return "{\"error\": \"" + msg + "\"}";
            }
        } catch (Exception e) {
            return "{}";
        }
    }

    private boolean isStopped(String executionId) {
        AgentExecution e = agentExecutionMapper.selectById(executionId);
        return e != null && STATUS_STOPPED.equals(e.getStatus());
    }

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

    private void publishEvent(String executionId, String eventType, Integer round, String message, Object payload, long startMs) {
        executionEventStreamService.publish(AgentExecutionEvent.builder()
                .eventType(eventType)
                .executionId(executionId)
                .roundNum(round)
                .message(message)
                .elapsedMs(elapsed(startMs))
                .payload(payload)
                .timestamp(Instant.now())
                .build());
    }
}
