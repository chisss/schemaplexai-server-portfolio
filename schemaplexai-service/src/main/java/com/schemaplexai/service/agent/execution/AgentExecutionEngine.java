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
    private static final int    MAX_TOOL_CALLS_PER_ROUND = 8;
    private static final int    LOG_CONTENT_LIMIT = 500;
    private static final int    MAX_MODEL_RETRIES = 2;
    private static final int    FALLBACK_EVIDENCE_LIMIT = 6;
    private static final int    FALLBACK_TEXT_LIMIT = 320;
    private static final String FORCE_COMPLETION_PROMPT =
            "工具与轮次预算已达到上限。不要继续调用任何工具，请基于当前已获得的信息直接输出最终结果。"
                    + "如果个别细节无法确认，请明确标注“待确认”，不要继续探索，并直接输出结构化 Markdown。";

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

        List<LangChain4jResolution> modelChain;
        if ("model_group".equals(ctx.getAgentModelType()) && StringUtils.hasText(ctx.getAgentModelGroupId())) {
            modelChain = aiModelRouter.resolveGroupChain(ctx.getAgentModelGroupId());
        } else {
            modelChain = aiModelRouter.resolveRouteChain(ctx.getModel());
        }
        LangChain4jResolution resolution = modelChain.getFirst();
        appendLog(executionId, agentId, tenantId, "DEBUG", "MODEL_RESOLVED", 0,
                "chainSize=" + modelChain.size() + ", provider="
                        + resolution.config().getProvider() + ", modelId=" + resolution.config().getModelId(), startMs);

        return runAgenticLoop(ctx, systemPrompt, chatMemory, modelChain, startMs, conversationId);
    }

    // =========================================================================
    //  Agentic Loop
    // =========================================================================

    private AgentExecutionResult runAgenticLoop(AgentExecutionContext ctx,
            String systemPrompt, ChatMemory chatMemory, List<LangChain4jResolution> modelChain, long startMs, String conversationId) {

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
        int     maxRounds        = normalizeLimit(ctx.getMaxRounds(), MAX_ROUNDS);
        int     maxToolCallsPerRound = normalizeLimit(ctx.getMaxToolCallsPerRound(), MAX_TOOL_CALLS_PER_ROUND);

        for (int round = 1; round <= maxRounds; round++) {
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
            ModelCallResult callResult;
            try {
                callResult = invokeModelWithRetry(modelChain, request, executionId, agentId, tenantId, round, startMs);
            } catch (Exception e) {
                AgentExecutionResult gracefulResult = handleRetryableModelFailure(
                        ctx, modelChain.getLast(), systemPrompt, chatMemory,
                        executionId, agentId, tenantId, round, maxRounds, startMs,
                        totalTokenInput, totalTokenOutput, conversationId, e
                );
                if (gracefulResult != null) {
                    return gracefulResult;
                }
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.error("AI 调用失败: executionId={}, round={}, error={}", executionId, round, errMsg);
                agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                        totalTokenInput, totalTokenOutput, null);
                publishEvent(executionId, "FAILED", round, "AI 调用失败: " + errMsg, null, startMs);
                return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).rounds(round).build();
            }
            ChatResponse response = callResult.response();

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

            List<ToolCall> limitedToolCalls = limitToolCalls(executionId, agentId, tenantId, round,
                    toolCalls, maxToolCallsPerRound, startMs);
            publishEvent(executionId, "TOOL_CALL", round,
                    "开始执行工具调用(" + limitedToolCalls.size() + "/" + toolCalls.size() + "个)", null, startMs);
            List<ToolResult> toolResults = new ArrayList<>(toolRegistry.executeAll(tenantId, agentId, limitedToolCalls));
            if (limitedToolCalls.size() < toolCalls.size()) {
                for (int i = limitedToolCalls.size(); i < toolCalls.size(); i++) {
                    toolResults.add(buildToolLimitResult(toolCalls.get(i), maxToolCallsPerRound));
                }
            }

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
            appendLog(executionId, agentId, tenantId, "WARN", "FORCE_COMPLETION", maxRounds,
                    "已达到最大执行轮次，进入强制收敛输出", startMs);
            try {
                ChatResponse forcedResponse = forceCompletion(modelChain.getLast(), systemPrompt, chatMemory,
                        executionId, agentId, tenantId, maxRounds + 1, startMs);
                var forcedTokenUsage = forcedResponse.metadata().tokenUsage();
                if (forcedTokenUsage != null) {
                    totalTokenInput += forcedTokenUsage.inputTokenCount();
                    totalTokenOutput += forcedTokenUsage.outputTokenCount();
                }
                AiMessage forcedAiMessage = forcedResponse.aiMessage();
                lastContent = forcedAiMessage != null && forcedAiMessage.text() != null ? forcedAiMessage.text() : "";
                chatMemory.add(UserMessage.from(FORCE_COMPLETION_PROMPT));
                if (forcedAiMessage != null) {
                    chatMemory.add(forcedAiMessage);
                }
                return buildCompletedResult(executionId, agentId, tenantId,
                        lastContent, totalTokenInput, totalTokenOutput, maxRounds + 1, startMs, conversationId);
            } catch (Exception forceException) {
                if (hasCompletionEvidence(chatMemory)) {
                    String degradedContent = buildDegradedCompletion(ctx, chatMemory, maxRounds, null, forceException);
                    appendLog(executionId, agentId, tenantId, "WARN", "DEGRADED_COMPLETION", maxRounds,
                            "强制收敛失败，基于已收集证据输出降级结论", startMs);
                    publishEvent(executionId, "AI_RESPONSE", maxRounds, "强制收敛失败，已使用降级结论完成节点", null, startMs);
                    chatMemory.add(UserMessage.from(FORCE_COMPLETION_PROMPT));
                    chatMemory.add(AiMessage.from(degradedContent));
                    return buildCompletedResult(executionId, agentId, tenantId,
                            degradedContent, totalTokenInput, totalTokenOutput, maxRounds, startMs, conversationId);
                }
                String errMsg = "超出最大执行轮次 " + maxRounds + "，且强制收敛失败: "
                        + (forceException.getMessage() != null ? forceException.getMessage() : forceException.getClass().getSimpleName());
                log.warn("Agent 超出最大轮次且强制收敛失败: executionId={}", executionId, forceException);
                appendLog(executionId, agentId, tenantId, "ERROR", "EXECUTION_FAILED", maxRounds, errMsg, startMs);
                agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                        totalTokenInput, totalTokenOutput, null);
                publishEvent(executionId, "FAILED", maxRounds, errMsg, null, startMs);
                return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
            }
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
        StringBuilder contextSb = new StringBuilder();
        inputContext.forEach((k, v) -> {
            if (k != null && k.startsWith("_")) {
                return;
            }
            if (v == null) {
                return;
            }
            contextSb.append("- ").append(k).append(": ").append(renderContextValue(v)).append("\n");
        });
        if (contextSb.isEmpty()) {
            return sb.toString().trim();
        }
        sb.append("---\n以下为补充上下文变量：\n").append(contextSb);
        return sb.toString().trim();
    }

    private String renderContextValue(Object value) {
        String text;
        try {
            text = value instanceof String ? (String) value : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            text = String.valueOf(value);
        }
        return text.length() > 500 ? text.substring(0, 500) + "...[截断]" : text;
    }

    private ModelCallResult invokeModelWithRetry(List<LangChain4jResolution> modelChain, ChatRequest request,
                                                 String executionId, String agentId, String tenantId,
                                                 int round, long startMs) throws Exception {
        Exception lastError = null;
        for (int index = 0; index < modelChain.size(); index++) {
            LangChain4jResolution resolution = modelChain.get(index);
            try {
                ChatResponse response = invokeModelWithRetry(resolution, request, executionId, agentId, tenantId, round, startMs);
                return new ModelCallResult(response, resolution);
            } catch (Exception e) {
                lastError = e;
                boolean hasFallback = index < modelChain.size() - 1;
                if (hasFallback) {
                    appendLog(executionId, agentId, tenantId, "WARN", "MODEL_FALLBACK_SWITCH", round,
                            "模型调用失败，切换至下一个降级模型: provider=" + resolution.config().getProvider()
                                    + ", modelId=" + resolution.config().getModelId()
                                    + ", error=" + resolveExceptionMessage(e), startMs);
                    continue;
                }
                throw e;
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("AI 调用失败");
    }

    private ChatResponse invokeModelWithRetry(LangChain4jResolution resolution, ChatRequest request,
                                              String executionId, String agentId, String tenantId,
                                              int round, long startMs) throws Exception {
        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_MODEL_RETRIES + 1; attempt++) {
            try {
                return resolution.model().chat(request);
            } catch (Exception e) {
                lastError = e;
                if (!isRetryableModelError(e) || attempt > MAX_MODEL_RETRIES) {
                    throw e;
                }
                String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("AI 调用失败，准备重试: executionId={}, round={}, attempt={}, error={}",
                        executionId, round, attempt, errMsg);
                appendLog(executionId, agentId, tenantId, "WARN", "MODEL_RETRY", round,
                        "模型调用失败，准备第 " + attempt + " 次重试: " + errMsg, startMs);
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw interruptedException;
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("AI 调用失败");
    }

    private boolean isRetryableModelError(Exception e) {
        String errMsg = e.getMessage();
        if (!StringUtils.hasText(errMsg)) {
            return false;
        }
        String normalized = errMsg.toLowerCase();
        return normalized.contains("api_error")
                || normalized.contains("unknown error (1000)")
                || normalized.contains("timeout")
                || normalized.contains("temporar")
                || normalized.contains("rate limit")
                || normalized.contains("429");
    }

    private int normalizeLimit(int configuredValue, int defaultValue) {
        return configuredValue > 0 ? configuredValue : defaultValue;
    }

    private ChatResponse forceCompletion(LangChain4jResolution resolution,
                                         String systemPrompt,
                                         ChatMemory chatMemory,
                                         String executionId,
                                         String agentId,
                                         String tenantId,
                                         int round,
                                         long startMs) throws Exception {
        String forceCompletionContext = buildForceCompletionContext(chatMemory);
        appendLog(executionId, agentId, tenantId, "INFO", "ROUND_START", round,
                "强制收敛轮开始，evidence=" + collectFallbackEvidence(chatMemory).size() + " items", startMs);
        publishEvent(executionId, "ROUND_START", round, "进入强制收敛轮", null, startMs);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(forceCompletionContext));

        ChatRequest forcedRequest = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(List.of())
                .build();

        ChatResponse response = invokeModelWithRetry(resolution, forcedRequest, executionId, agentId, tenantId, round, startMs);
        String preview = response.aiMessage() != null && response.aiMessage().text() != null
                ? response.aiMessage().text() : "";
        if (preview.length() > LOG_CONTENT_LIMIT) {
            preview = preview.substring(0, LOG_CONTENT_LIMIT) + "...[截断]";
        }
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO", "AI_RESPONSE",
                round, null, preview,
                response.metadata().tokenUsage() != null ? (int) response.metadata().tokenUsage().outputTokenCount() : 0,
                elapsed(startMs));
        publishEvent(executionId, "AI_RESPONSE", round, "已收到强制收敛响应", null, startMs);
        return response;
    }

    private AgentExecutionResult handleRetryableModelFailure(AgentExecutionContext ctx,
                                                             LangChain4jResolution resolution,
                                                             String systemPrompt,
                                                             ChatMemory chatMemory,
                                                             String executionId,
                                                             String agentId,
                                                             String tenantId,
                                                             int round,
                                                             int maxRounds,
                                                             long startMs,
                                                             long totalTokenInput,
                                                             long totalTokenOutput,
                                                             String conversationId,
                                                             Exception modelException) {
        if (!shouldGracefullyCompleteOnModelFailure(modelException, round, maxRounds, chatMemory)) {
            return null;
        }

        appendLog(executionId, agentId, tenantId, "WARN", "MODEL_FAILURE_FALLBACK", round,
                "模型在第 " + round + " 轮持续失败，尝试强制收敛完成当前节点", startMs);

        try {
            ChatResponse forcedResponse = forceCompletion(resolution, systemPrompt, chatMemory,
                    executionId, agentId, tenantId, round + 1, startMs);
            var forcedTokenUsage = forcedResponse.metadata().tokenUsage();
            long mergedTokenInput = totalTokenInput;
            long mergedTokenOutput = totalTokenOutput;
            if (forcedTokenUsage != null) {
                mergedTokenInput += forcedTokenUsage.inputTokenCount();
                mergedTokenOutput += forcedTokenUsage.outputTokenCount();
            }

            AiMessage forcedAiMessage = forcedResponse.aiMessage();
            String forcedContent = forcedAiMessage != null ? forcedAiMessage.text() : null;
            if (!StringUtils.hasText(forcedContent)) {
                forcedContent = buildDegradedCompletion(ctx, chatMemory, round, modelException, null);
            }

            chatMemory.add(UserMessage.from(FORCE_COMPLETION_PROMPT));
            chatMemory.add(AiMessage.from(forcedContent));
            return buildCompletedResult(executionId, agentId, tenantId,
                    forcedContent, mergedTokenInput, mergedTokenOutput, round + 1, startMs, conversationId);
        } catch (Exception forceException) {
            if (!hasCompletionEvidence(chatMemory)) {
                return null;
            }
            String degradedContent = buildDegradedCompletion(ctx, chatMemory, round, modelException, forceException);
            appendLog(executionId, agentId, tenantId, "WARN", "DEGRADED_COMPLETION", round,
                    "模型调用与强制收敛均失败，基于已收集证据输出降级结论", startMs);
            publishEvent(executionId, "AI_RESPONSE", round, "模型异常，已使用降级结论完成节点", null, startMs);
            chatMemory.add(UserMessage.from(FORCE_COMPLETION_PROMPT));
            chatMemory.add(AiMessage.from(degradedContent));
            return buildCompletedResult(executionId, agentId, tenantId,
                    degradedContent, totalTokenInput, totalTokenOutput, round, startMs, conversationId);
        }
    }

    private boolean shouldGracefullyCompleteOnModelFailure(Exception exception,
                                                           int round,
                                                           int maxRounds,
                                                           ChatMemory chatMemory) {
        if (!isRetryableModelError(exception)) {
            return false;
        }
        return round >= maxRounds || round >= 3 && hasCompletionEvidence(chatMemory);
    }

    private boolean hasCompletionEvidence(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null) {
            return false;
        }
        int evidenceCount = 0;
        for (ChatMessage message : chatMemory.messages()) {
            if (message instanceof AiMessage aiMessage && StringUtils.hasText(aiMessage.text())) {
                evidenceCount++;
            } else if (message instanceof ToolExecutionResultMessage toolMessage
                    && StringUtils.hasText(toolMessage.text())) {
                evidenceCount++;
            }
            if (evidenceCount >= 2) {
                return true;
            }
        }
        return false;
    }

    private String buildDegradedCompletion(AgentExecutionContext ctx,
                                           ChatMemory chatMemory,
                                           int round,
                                           Exception primaryException,
                                           Exception forcedException) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 阶段性技术结论（降级收敛）").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 任务目标").append(System.lineSeparator());
        builder.append("- ").append(compactText(ctx != null ? ctx.getInputPrompt() : "生成本节点要求的结构化 Markdown 结果", 220))
                .append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("## 已确认信息").append(System.lineSeparator());
        List<String> evidences = collectFallbackEvidence(chatMemory);
        if (evidences.isEmpty()) {
            builder.append("- 当前未收集到足够证据，请在后续节点或人工复核时补充。").append(System.lineSeparator());
        } else {
            for (String evidence : evidences) {
                builder.append("- ").append(evidence).append(System.lineSeparator());
            }
        }
        builder.append(System.lineSeparator());

        builder.append("## 当前结论").append(System.lineSeparator());
        builder.append("- 已基于前序轮次收集的信息完成收敛输出，可继续驱动后续工作流节点。").append(System.lineSeparator());
        builder.append("- 模型在第 ").append(round).append(" 轮发生瞬时异常，当前结果为保守结论，优先保证链路连续性。")
                .append(System.lineSeparator()).append(System.lineSeparator());

        builder.append("## 风险与待确认").append(System.lineSeparator());
        builder.append("- 模型异常: ").append(compactText(resolveExceptionMessage(primaryException), 180))
                .append(System.lineSeparator());
        if (forcedException != null) {
            builder.append("- 强制收敛异常: ").append(compactText(resolveExceptionMessage(forcedException), 180))
                    .append(System.lineSeparator());
        }
        builder.append("- 如需更完整结论，可在模型恢复后重新触发当前节点或复跑工作流。").append(System.lineSeparator());
        return builder.toString();
    }

    private List<String> collectFallbackEvidence(ChatMemory chatMemory) {
        List<String> evidences = new ArrayList<>();
        if (chatMemory == null || chatMemory.messages() == null) {
            return evidences;
        }
        List<ChatMessage> messages = chatMemory.messages();
        for (int i = messages.size() - 1; i >= 0 && evidences.size() < FALLBACK_EVIDENCE_LIMIT; i--) {
            ChatMessage message = messages.get(i);
            if (message instanceof ToolExecutionResultMessage toolMessage && StringUtils.hasText(toolMessage.text())) {
                evidences.add(0, "工具[" + toolMessage.toolName() + "] => "
                        + compactText(toolMessage.text(), FALLBACK_TEXT_LIMIT));
            } else if (message instanceof AiMessage aiMessage && StringUtils.hasText(aiMessage.text())) {
                evidences.add(0, "AI结论 => " + compactText(aiMessage.text(), FALLBACK_TEXT_LIMIT));
            }
        }
        return evidences;
    }

    private String buildForceCompletionContext(ChatMemory chatMemory) {
        StringBuilder builder = new StringBuilder();
        builder.append("请基于以下已收集证据直接输出最终 Markdown，不要再调用工具。").append(System.lineSeparator())
                .append(System.lineSeparator());

        String taskSummary = extractTaskSummary(chatMemory);
        if (StringUtils.hasText(taskSummary)) {
            builder.append("任务摘要: ").append(compactText(taskSummary, 220)).append(System.lineSeparator())
                    .append(System.lineSeparator());
        }

        List<String> evidences = collectFallbackEvidence(chatMemory);
        if (evidences.isEmpty()) {
            builder.append("- 当前没有可用证据，请直接输出一个保守但可交付的 Markdown 结果，并明确待确认项。");
            return builder.toString();
        }

        builder.append("已收集证据:").append(System.lineSeparator());
        for (String evidence : evidences) {
            builder.append("- ").append(evidence).append(System.lineSeparator());
        }
        builder.append(System.lineSeparator()).append(FORCE_COMPLETION_PROMPT);
        return builder.toString();
    }

    private String extractTaskSummary(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null) {
            return null;
        }
        for (ChatMessage message : chatMemory.messages()) {
            if (message instanceof UserMessage userMessage && StringUtils.hasText(userMessage.singleText())) {
                return userMessage.singleText();
            }
        }
        return null;
    }

    private String resolveExceptionMessage(Exception exception) {
        return exception != null && StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : exception != null ? exception.getClass().getSimpleName() : "unknown";
    }

    private String compactText(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return "待确认";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(limit, 1)) + "...";
    }

    private List<ToolCall> limitToolCalls(String executionId,
                                          String agentId,
                                          String tenantId,
                                          int round,
                                          List<ToolCall> toolCalls,
                                          int maxToolCallsPerRound,
                                          long startMs) {
        if (toolCalls == null || toolCalls.isEmpty() || toolCalls.size() <= maxToolCallsPerRound) {
            return toolCalls;
        }
        appendLog(executionId, agentId, tenantId, "WARN", "TOOL_CALL_LIMITED", round,
                "本轮工具调用数 " + toolCalls.size() + " 超过上限 " + maxToolCallsPerRound + "，仅执行前 "
                        + maxToolCallsPerRound + " 个，其余调用将返回限流提示",
                startMs);
        return new ArrayList<>(toolCalls.subList(0, maxToolCallsPerRound));
    }

    private ToolResult buildToolLimitResult(ToolCall toolCall, int maxToolCallsPerRound) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage("本轮工具调用数量超过限制 " + maxToolCallsPerRound + "，请基于已有结果收敛并直接输出结论")
                .build();
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

    private record ModelCallResult(ChatResponse response, LangChain4jResolution resolution) {
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
