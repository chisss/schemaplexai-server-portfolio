package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.AgentLoopPromptConstant;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentLoopLogTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.CompletionRevisionResult;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.AgentLoopToolHandler.ToolExecutionOutcome;
import com.schemaplexai.service.agent.execution.AgentModelInvoker.ModelCallResult;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.memory.AgentMemoryExtractionService;
import com.schemaplexai.service.agent.memory.AgentInstructionsAutoService;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.util.FileContentExtractor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Agent 执行引擎 — Agentic Loop 实现（基于 LangChain4j）
 *
 * <p>执行流程（最多 {@code maxRounds} 轮，由 {@link AgentEngineParams} 控制）：
 * <ol>
 *   <li>构建四层 System Prompt（{@link ContextInjector}）</li>
 *   <li>初始化持久化 ChatMemory（Redis L1 + PostgreSQL L2）</li>
 *   <li>解析 AI 模型配置（{@link AIModelRouter}）</li>
 *   <li>Agentic Loop：调用 AI → 有工具调用时执行工具并 continue → 无工具调用时退出</li>
 *   <li>超出轮次或空响应时触发强制收敛，失败则降级收敛</li>
 * </ol>
 * </p>
 *
 * <p>各职责已拆分到专职类：
 * <ul>
 *   <li>{@link AgentModelInvoker} — 模型调用、超时、重试、降级链</li>
 *   <li>{@link AgentLoopToolHandler} — 工具过滤、执行、结果序列化</li>
 *   <li>{@link AgentLoopCompletionHandler} — 强制收敛、质量修订、降级收敛</li>
 *   <li>{@link AgentLoopQualityChecker} — 质量检测与输出清洗</li>
 *   <li>{@link AgentEngineConfigLoader} — 从数据库加载执行参数</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentExecutionEngine {

    private static final String STATUS_PAUSED    = AgentExecutionStatusEnum.PAUSED.getCode();
    private static final String STATUS_RUNNING   = AgentExecutionStatusEnum.RUNNING.getCode();
    private static final String STATUS_STOPPED   = AgentExecutionStatusEnum.STOPPED.getCode();
    private static final String STATUS_COMPLETED = AgentExecutionStatusEnum.COMPLETED.getCode();
    private static final String STATUS_FAILED    = AgentExecutionStatusEnum.FAILED.getCode();

    private final AgentLogService              agentLogService;
    private final ContextInjector              contextInjector;
    private final AIModelRouter                aiModelRouter;
    private final AgentExecutionMapper         agentExecutionMapper;
    private final ObjectMapper                 objectMapper;
    private final ExecutionEventStreamService  executionEventStreamService;
    private final AgentToolSessionFactory      agentToolSessionFactory;
    private final CompositeChatMemoryStore     compositeChatMemoryStore;
    private final AgentEngineConfigLoader      engineConfigLoader;
    private final AgentModelInvoker            modelInvoker;
    private final AgentLoopToolHandler         toolHandler;
    private final AgentLoopCompletionHandler   completionHandler;
    private final AgentLoopQualityChecker      qualityChecker;
    private final AgentLoopShadowReviewService shadowReviewService;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;
    private final DocumentStorageService       documentStorageService;
    private final FileContentExtractor         fileContentExtractor;
    private final TokenEstimatorSupport        tokenEstimatorSupport = new TokenEstimatorSupport();
    private final AgentChatMemoryCompactor     chatMemoryCompactor = new AgentChatMemoryCompactor(tokenEstimatorSupport);

    @Lazy
    @Autowired(required = false)
    private AgentMemoryExtractionService agentMemoryExtractionService;

    @Lazy
    @Autowired(required = false)
    private AgentInstructionsAutoService agentInstructionsAutoService;

    // =========================================================================
    //  公开入口
    // =========================================================================

    /**
     * 异步执行 Agent（首次执行）
     *
     * @param ctx 执行上下文
     */
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> execute(AgentExecutionContext ctx) {
        if (!isValidContext(ctx)) {
            log.error("Agent 执行上下文非法: {}", ctx);
            return done(AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage("执行上下文缺失").build());
        }
        long startMs = System.currentTimeMillis();
        agentLogService.updateExecutionStatus(ctx.getExecutionId(), STATUS_RUNNING, null, null, null, null);
        log.info("Agent 执行开始: executionId={}, agentId={}, model={}", ctx.getExecutionId(), ctx.getAgentId(), ctx.getModel());
        try {
            return done(doExecute(ctx, null, startMs));
        } catch (Exception e) {
            log.error("Agent 执行异常: executionId={}", ctx.getExecutionId(), e);
            return done(handleFatalError(ctx, e, startMs));
        }
    }

    /**
     * 异步恢复 Agent 执行（人工输入后继续）
     */
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> resume(AgentExecutionContext ctx, AgentExecutionInputDTO input) {
        if (!isValidContext(ctx) || input == null) {
            log.error("Agent 恢复上下文非法: ctx={}, input={}", ctx, input);
            return done(AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage("恢复执行上下文缺失").build());
        }
        long startMs = System.currentTimeMillis();
        agentLogService.updateExecutionStatus(ctx.getExecutionId(), STATUS_RUNNING, null, null, null, null);
        log.info("Agent 恢复执行: executionId={}, agentId={}", ctx.getExecutionId(), ctx.getAgentId());
        try {
            return done(doExecute(ctx, input, startMs));
        } catch (Exception e) {
            log.error("Agent 恢复执行异常: executionId={}", ctx.getExecutionId(), e);
            return done(handleFatalError(ctx, e, startMs));
        }
    }

    // =========================================================================
    //  编排层
    // =========================================================================

    /**
     * 核心编排：构建 SystemPrompt、初始化 ChatMemory、解析模型链，然后进入 Agentic Loop
     */
    private AgentExecutionResult doExecute(AgentExecutionContext ctx, AgentExecutionInputDTO resumeInput, long startMs) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();

        // 加载执行参数（数据库配置优先，ctx 中的值次之，最后回退默认值）
        AgentEngineParams params = engineConfigLoader.load(agentId, ctx);
        applyRuntimeInstructions(ctx);

        // 解析模型链
        List<LangChain4jResolution> modelChain = resolveModelChain(ctx);
        LangChain4jResolution first = modelChain.getFirst();

        // 为上下文压缩设置小模型（优先选择低成本模型）
        AiModelConfig compactModelConfig = aiModelRouter.resolveCompactModel(tenantId);
        if (compactModelConfig != null) {
            chatMemoryCompactor.setCompactionModel(aiModelRouter.buildChatModel(compactModelConfig));
        }

        // 首次执行时自动创建专属指令（幂等，非首次仅一次 SELECT）
        if (agentInstructionsAutoService != null) {
            try {
                agentInstructionsAutoService.autoInitIfAbsent(tenantId, agentId);
            } catch (Exception e) {
                log.warn("自动初始化Agent专属指令失败: agentId={}, error={}", agentId, e.getMessage());
            }
        }

        // 构建 System Prompt
        String extraContext  = buildExtraContext(ctx);
        ContextInjector.PromptBuildResult promptBuildResult = contextInjector.buildSystemPromptDetail(
                agentId, extraContext, tenantId, ctx.getTeamAgentId(), ctx.getAdditionalSystemContexts(), first.config());
        String systemPrompt  = promptBuildResult.prompt();
        logSystemPromptBuilt(executionId, agentId, tenantId, promptBuildResult, ctx.getModel(), startMs);

        // 初始化 ChatMemory
        String conversationId = resolveConversationId(ctx);
        persistConversationId(executionId, conversationId);
        ChatMemory chatMemory = chatMemoryCompactor.createChatMemory(
                conversationId, params, first.config(), compositeChatMemoryStore);

        // 添加用户消息（首次执行或恢复执行）
        if (resumeInput == null) {
            chatMemory.add(UserMessage.from(buildUserMessage(ctx)));
        } else {
            addResumeMessage(executionId, agentId, tenantId, chatMemory, resumeInput, startMs);
        }
        log.info("ChatMemory 初始化完成: conversationId={}, 历史消息数={}", conversationId, chatMemory.messages().size());
        agentLogService.appendLog(executionId, agentId, tenantId, "DEBUG",
                AgentLoopLogTypeEnum.MODEL_RESOLVED.getCode(), 0, null,
                "chainSize=" + modelChain.size() + ", provider=" + first.config().getProvider()
                        + ", modelId=" + first.config().getModelId(), null, elapsed(startMs));

        return runAgenticLoop(ctx, systemPrompt, chatMemory, modelChain, params, startMs, conversationId);
    }

    // =========================================================================
    //  Agentic Loop
    // =========================================================================

    /**
     * Agentic Loop 主循环：每轮调用 AI → 处理工具调用 → 检测收敛条件
     */
    private AgentExecutionResult runAgenticLoop(AgentExecutionContext ctx,
                                                 String systemPrompt,
                                                 ChatMemory chatMemory,
                                                 List<LangChain4jResolution> modelChain,
                                                 AgentEngineParams params,
                                                 long startMs,
                                                 String conversationId) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();
        AgentLoopState state = new AgentLoopState();
        int maxLoopRounds = params.getMaxRounds() + params.getMaxQualityReflections();

        try (AgentToolSessionFactory.AgentToolSession toolSession = agentToolSessionFactory.openSession(ctx, conversationId)) {
            String effectiveSystemPrompt = toolSession.augmentSystemPrompt(systemPrompt);

            for (int round = 1; round <= maxLoopRounds; round++) {
                state.setLastRound(round);

                // 检查是否被用户停止
                if (isStopped(executionId)) {
                    return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
                }

                consumePendingShadowReviewIfReady(chatMemory, state, params,
                        executionId, agentId, tenantId, round, startMs);

                // 准备本轮工具列表
                AgentToolSessionFactory.RoundToolContext roundCtx = toolSession.buildRoundContext(chatMemory, round);
                List<ToolSpecification> effectiveTools = state.isQualityReflectionPending()
                        ? List.of() : nullSafe(roundCtx.toolServiceContext().effectiveTools());
                logRoundStart(executionId, agentId, tenantId, round, chatMemory, effectiveTools, state, startMs);
                state.setQualityReflectionPending(false);

                // 调用 AI 模型
                ModelCallResult callResult;
                try {
                    AiModelConfig activeModelConfig = modelChain.getFirst().config();
                    AgentChatMemoryCompactor.CompactionResult compactionResult =
                            chatMemoryCompactor.compactIfNeeded(chatMemory, params, activeModelConfig);
                    AgentChatMemoryCompactor.NormalizationResult normalizationResult =
                            chatMemoryCompactor.normalizeForRequest(chatMemory);
                    List<ChatMessage> messages = buildMessages(effectiveSystemPrompt, chatMemory);
                    logContextBudget(executionId, agentId, tenantId, round,
                            effectiveSystemPrompt, chatMemory, messages, activeModelConfig,
                            compactionResult, normalizationResult, startMs);
                    ChatRequest request = ChatRequest.builder().messages(messages).toolSpecifications(effectiveTools).build();
                    callResult = modelInvoker.invokeChainWithRetry(modelChain, request, params,
                            executionId, agentId, tenantId, round, startMs, agentLogService);
                } catch (Exception e) {
                    AgentExecutionResult graceful = handleModelFailure(ctx, modelChain.getLast(), effectiveSystemPrompt,
                            chatMemory, params, state, executionId, agentId, tenantId, round, startMs, conversationId, e);
                    if (graceful != null) return graceful;
                    return buildFailedResult(executionId, agentId, tenantId, round, state, startMs, resolveExMsg(e));
                }

                // 处理 AI 响应
                ChatResponse response = callResult.response();
                updateTokenUsage(state, response);
                AiMessage aiMsg = response.aiMessage();
                String lastContent = aiMsg != null && aiMsg.text() != null ? aiMsg.text() : "";
                state.setLastContent(lastContent);
                String finishReason = response.metadata().finishReason() != null
                        ? response.metadata().finishReason().toString() : "STOP";
                logAiResponse(executionId, agentId, tenantId, round, response, finishReason, params, startMs);

                // 分离可执行工具请求与被拒绝工具请求
                List<ToolExecutionRequest> allRequests = aiMsg != null && aiMsg.toolExecutionRequests() != null
                        ? aiMsg.toolExecutionRequests() : List.of();
                List<ToolExecutionRequest> executableRequests = toolHandler.filterExecutable(allRequests, effectiveTools);
                List<ToolExecutionRequest> rejectedRequests   = allRequests.stream()
                        .filter(r -> !executableRequests.contains(r)).toList();

                // 无工具调用分支
                if (allRequests.isEmpty()) {
                    AgentExecutionResult r = handleNoToolCall(ctx, chatMemory, aiMsg, state, params,
                            executionId, agentId, tenantId, round, finishReason, startMs, conversationId);
                    if (r != null) return r;
                    if (state.isLoopCompleted()) break;
                    continue;
                }

                // 有工具调用但全部被拒绝
                if (executableRequests.isEmpty()) {
                    AgentExecutionResult r = handleUnexpectedToolCall(chatMemory, aiMsg, state, params,
                            executionId, agentId, tenantId, round, startMs, conversationId);
                    if (r != null) return r;
                    if (state.isLoopCompleted()) break;
                    continue;
                }

                // 执行工具调用
                state.setUnexpectedToolCallRecoveries(0);
                AgentExecutionResult interrupted = executeToolsAndCheckInterrupt(ctx, roundCtx, chatMemory, aiMsg,
                        executableRequests, rejectedRequests, state, params,
                        executionId, agentId, tenantId, round, startMs, conversationId);
                if (interrupted != null) return interrupted;
            }
        }

        // 循环结束后处理（正常完成 or 强制收敛）
        return finalizeLoop(ctx, modelChain, systemPrompt, chatMemory, state, params,
                executionId, agentId, tenantId, startMs, conversationId);
    }

    // =========================================================================
    //  Loop 内部处理方法
    // =========================================================================

    /**
     * 处理无工具调用的 AI 响应：截断续写、空响应强制收敛、质量反思、正常完成
     * 返回非 null 表示需要立即退出循环并返回该结果
     */
    private AgentExecutionResult handleNoToolCall(AgentExecutionContext ctx,
                                                   ChatMemory chatMemory,
                                                   AiMessage aiMsg,
                                                   AgentLoopState state,
                                                   AgentEngineParams params,
                                                   String executionId, String agentId, String tenantId,
                                                   int round, String finishReason, long startMs, String conversationId) {
        state.setUnexpectedToolCallRecoveries(0);

        // 输出被 maxTokens 截断，追加续写提示继续
        if (isOutputTruncated(finishReason)) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.OUTPUT_TRUNCATED.getCode(), round, null,
                    "模型输出被 maxTokens 截断，追加继续提示并继续执行", null, elapsed(startMs));
            if (aiMsg != null) chatMemory.add(aiMsg);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.CONTINUE_TRUNCATED));
            return null;
        }

        // 空响应但有历史证据 → 触发强制收敛
        if (!StringUtils.hasText(state.getLastContent()) && completionHandler.hasCompletionEvidence(chatMemory)) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.EMPTY_AI_RESPONSE.getCode(), round, null,
                    "模型返回空响应，进入强制收敛输出", null, elapsed(startMs));
            state.setForceCompletionRequested(true);
            state.setLoopCompleted(false);
            return null; // 由 finalizeLoop 处理
        }

        // 空响应且无历史证据 → 直接失败
        if (!StringUtils.hasText(state.getLastContent())) {
            return buildFailedResult(executionId, agentId, tenantId, round, state, startMs,
                    AgentLoopPromptConstant.EMPTY_AI_RESPONSE_ERROR);
        }

        if (!params.isShadowQualityReviewEnabled()) {
            QualityReflectionFeedback feedback = qualityChecker.buildFeedback(ctx, state.getLastContent(), chatMemory,
                    state.getQualityReflectionCount(), params);
            if (feedback != null) {
                chatMemory.add(aiMsg);
                applyQualityFeedback(chatMemory, state, feedback,
                        executionId, agentId, tenantId, round, startMs, false);
                return null;
            }
            chatMemory.add(aiMsg);
            state.setLoopCompleted(true);
            return null;
        }

        chatMemory.add(aiMsg);
        QualityReflectionFeedback immediateFeedback = qualityChecker.buildImmediateFeedback(
                ctx, state.getLastContent(), chatMemory, state.getQualityReflectionCount(), params
        );
        if (immediateFeedback != null) {
            applyQualityFeedback(chatMemory, state, immediateFeedback,
                    executionId, agentId, tenantId, round, startMs, false);
            return null;
        }

        scheduleShadowReviewIfNeeded(ctx, state, params, executionId, agentId, tenantId, round, startMs);
        QualityReflectionFeedback shadowFeedback = consumePendingShadowReview(chatMemory, state, params,
                executionId, agentId, tenantId, round, startMs, true);
        if (shadowFeedback != null) {
            return null;
        }

        state.setLoopCompleted(true);
        return null;
    }

    /**
     * 处理非预期工具调用（当前轮次未开放工具但模型仍返回工具调用）
     * 返回非 null 表示需要立即退出循环
     */
    private AgentExecutionResult handleUnexpectedToolCall(ChatMemory chatMemory,
                                                           AiMessage aiMsg,
                                                           AgentLoopState state,
                                                           AgentEngineParams params,
                                                           String executionId, String agentId, String tenantId,
                                                           int round, long startMs, String conversationId) {
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.UNEXPECTED_TOOL_REQUEST.getCode(), round, null,
                "当前轮次未开放工具，但模型仍返回工具调用，拒绝执行并要求直接收敛", null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, "UNEXPECTED_TOOL_REQUEST", round,
                "当前轮次未开放工具，已拒绝模型返回的工具调用", startMs);

        if (StringUtils.hasText(state.getLastContent())) {
            chatMemory.add(AiMessage.from(state.getLastContent()));
            state.setUnexpectedToolCallRecoveries(0);
            state.setLoopCompleted(true);
            return null;
        }
        if (completionHandler.hasCompletionEvidence(chatMemory)) {
            state.setForceCompletionRequested(true);
            return null;
        }
        if (state.getUnexpectedToolCallRecoveries() >= params.getMaxUnexpectedToolCallRecoveries()) {
            return buildFailedResult(executionId, agentId, tenantId, round, state, startMs,
                    "模型在禁用工具轮次仍持续请求工具，且未返回可用文本");
        }
        chatMemory.add(UserMessage.from(AgentLoopPromptConstant.NO_TOOL_CALL_RECOVERY));
        state.setQualityReflectionPending(true);
        state.setUnexpectedToolCallRecoveries(state.getUnexpectedToolCallRecoveries() + 1);
        return null;
    }

    /**
     * 执行工具调用并检测安全中断（PAUSE/BLOCK）
     * 返回非 null 表示需要立即退出循环
     */
    private AgentExecutionResult executeToolsAndCheckInterrupt(AgentExecutionContext ctx,
                                                                AgentToolSessionFactory.RoundToolContext roundCtx,
                                                                ChatMemory chatMemory,
                                                                AiMessage aiMsg,
                                                                List<ToolExecutionRequest> executableRequests,
                                                                List<ToolExecutionRequest> rejectedRequests,
                                                                AgentLoopState state,
                                                                AgentEngineParams params,
                                                                String executionId, String agentId, String tenantId,
                                                                int round, long startMs, String conversationId) {
        List<com.schemaplexai.service.agent.tool.model.ToolCall> toolCalls = toolHandler.toToolCalls(executableRequests);
        List<com.schemaplexai.service.agent.tool.model.ToolCall> limited = toolHandler.limitToolCalls(
                executionId, agentId, tenantId, round, toolCalls, params.getMaxToolCallsPerRound(), agentLogService, startMs);
        executionEventStreamService.publishSimple(executionId, "TOOL_CALL", round,
                "开始执行工具调用(" + limited.size() + "/" + toolCalls.size() + "个)", startMs);
        chatMemory.add(aiMsg);

        List<ToolResult> toolResults = new ArrayList<>();
        int execCount = limited.size();
        AgentLoopToolHandler.ToolResultCompressionOptions compressionOptions =
                new AgentLoopToolHandler.ToolResultCompressionOptions(
                        params.getMaxToolResultMessageLength(),
                        params.getToolRequestSummaryLimit()
                );

        // 执行允许的工具
        for (int i = 0; i < execCount; i++) {
            ToolExecutionOutcome outcome = toolHandler.executeOne(roundCtx, executableRequests.get(i), compressionOptions);
            toolResults.add(outcome.toolResult());
            chatMemory.add(outcome.resultMessage());
        }
        // 超限工具返回限流提示
        for (int i = execCount; i < toolCalls.size(); i++) {
            ToolResult limitResult = toolHandler.buildLimitResult(toolCalls.get(i), params.getMaxToolCallsPerRound());
            toolResults.add(limitResult);
            chatMemory.add(toolHandler.buildResultMessage(executableRequests.get(i), limitResult, Map.of(), true, null, compressionOptions));
        }
        // 被拒绝工具返回拒绝提示
        for (ToolExecutionRequest rejected : rejectedRequests) {
            ToolResult rejectedResult = toolHandler.buildRejectedResult(rejected);
            toolResults.add(rejectedResult);
            chatMemory.add(toolHandler.buildResultMessage(rejected, rejectedResult, Map.of(), true, null, compressionOptions));
        }

        executionEventStreamService.publishSimple(executionId, "TOOL_RESULT", round,
                "工具调用完成(" + toolResults.size() + "个)", startMs);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.TOOL_RESULT.getCode(), round, null,
                "工具调用完成，数量=" + toolResults.size(), null, elapsed(startMs));

        return resolveSecurityInterrupt(ctx, executionId, agentId, tenantId, round,
                toolResults, state, startMs, conversationId);
    }

    /**
     * 检测工具结果中的安全中断（PAUSE/BLOCK），返回对应结果；无中断返回 null
     */
    private AgentExecutionResult resolveSecurityInterrupt(AgentExecutionContext ctx,
                                                           String executionId, String agentId, String tenantId,
                                                           int round, List<ToolResult> toolResults,
                                                           AgentLoopState state, long startMs, String conversationId) {
        ToolResult interrupted = findInterruptedToolResult(toolResults);
        if (interrupted == null || !StringUtils.hasText(interrupted.getControlAction())) return null;

        String message = StringUtils.hasText(interrupted.getErrorMessage())
                ? interrupted.getErrorMessage() : "工具执行命中安全策略";
        Map<String, Object> payload = Map.of("toolCode", interrupted.getToolCode(),
                "controlAction", interrupted.getControlAction());

        if (SecurityComplianceConstant.DECISION_PAUSE.equals(interrupted.getControlAction())) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.SECURITY_PAUSED.getCode(), round, null, message, null, elapsed(startMs));
            agentLogService.updateExecutionStatus(executionId, STATUS_PAUSED, message,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), null);
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.REQUIRE_INPUT.getCode(), round, message, payload, startMs);
            return AgentExecutionResult.builder().status(STATUS_PAUSED).errorMessage(message)
                    .conversationId(conversationId).tokenInput(state.getTotalTokenInput())
                    .tokenOutput(state.getTotalTokenOutput()).rounds(round).build();
        }
        if (SecurityComplianceConstant.DECISION_BLOCK.equals(interrupted.getControlAction())) {
            agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                    AgentLoopLogTypeEnum.SECURITY_BLOCKED.getCode(), round, null, message, null, elapsed(startMs));
            agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, message,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), null);
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.BLOCKED.getCode(), round, message, payload, startMs);
            return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(message)
                    .conversationId(conversationId).tokenInput(state.getTotalTokenInput())
                    .tokenOutput(state.getTotalTokenOutput()).rounds(round).build();
        }
        return null;
    }

    /**
     * 循环结束后的收尾：正常完成直接构建结果；否则触发强制收敛
     */
    private AgentExecutionResult finalizeLoop(AgentExecutionContext ctx,
                                               List<LangChain4jResolution> modelChain,
                                               String systemPrompt, ChatMemory chatMemory,
                                               AgentLoopState state, AgentEngineParams params,
                                               String executionId, String agentId, String tenantId,
                                               long startMs, String conversationId) {
        if (state.isLoopCompleted()) {
            if (params.isShadowQualityReviewEnabled()) {
                CompletionRevisionResult revision = reviseWithPendingShadowReview(ctx, modelChain.getLast(), systemPrompt,
                        chatMemory, state, params, executionId, agentId, tenantId, startMs);
                state.setLastContent(revision.content());
                state.addTokenUsage(revision.tokenInput(), revision.tokenOutput());
            }
            return buildCompletedResult(executionId, agentId, tenantId, state, startMs, conversationId);
        }

        // 强制收敛
        int forceRound = state.getLastRound() + (state.isForceCompletionRequested() ? 0 : 1);
        String forceMsg = state.isForceCompletionRequested()
                ? "模型返回空响应，进入强制收敛输出" : "已达到最大执行轮次，进入强制收敛输出";
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.FORCE_COMPLETION.getCode(), state.getLastRound(), null, forceMsg, null, elapsed(startMs));

        try {
            ChatResponse forced = completionHandler.forceCompletion(modelChain.getLast(), systemPrompt, chatMemory,
                    params, executionId, agentId, tenantId, forceRound, startMs, agentLogService, executionEventStreamService);
            updateTokenUsage(state, forced);
            AiMessage forcedMsg = forced.aiMessage();
            state.setLastContent(forcedMsg != null && forcedMsg.text() != null ? forcedMsg.text() : "");
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            if (forcedMsg != null) chatMemory.add(forcedMsg);

            // 强制收敛后质量修订
            CompletionRevisionResult revision = completionHandler.reviseIfNecessary(ctx, modelChain.getLast(),
                    systemPrompt, chatMemory, state.getLastContent(), params,
                    executionId, agentId, tenantId, forceRound + 1, startMs, agentLogService, executionEventStreamService);
            state.setLastContent(revision.content());
            state.addTokenUsage(revision.tokenInput(), revision.tokenOutput());
            if (revision.roundsUsed() > 0) forceRound = revision.finalRound();

            return buildCompletedResult(executionId, agentId, tenantId, state, startMs, conversationId);
        } catch (Exception forceEx) {
            if (completionHandler.hasCompletionEvidence(chatMemory)) {
                String degraded = completionHandler.buildDegraded(ctx, chatMemory, state.getLastRound(), null, forceEx, params);
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.DEGRADED_COMPLETION.getCode(), state.getLastRound(), null,
                        "强制收敛失败，基于已收集证据输出降级结论", null, elapsed(startMs));
                chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
                chatMemory.add(AiMessage.from(degraded));
                state.setLastContent(degraded);
                return buildCompletedResult(executionId, agentId, tenantId, state, startMs, conversationId);
            }
            String errMsg = (state.isForceCompletionRequested() ? "模型返回空响应，且强制收敛失败: " : "超出最大执行轮次，且强制收敛失败: ")
                    + resolveExMsg(forceEx);
            agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errMsg,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), null);
            executionEventStreamService.publishSimple(executionId, "FAILED", state.getLastRound(), errMsg, startMs);
            return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
        }
    }

    /**
     * 模型调用失败时的降级处理：尝试强制收敛，失败则降级收敛
     * 返回 null 表示无法降级，由调用方继续处理
     */
    private AgentExecutionResult handleModelFailure(AgentExecutionContext ctx,
                                                     LangChain4jResolution resolution,
                                                     String systemPrompt, ChatMemory chatMemory,
                                                     AgentEngineParams params, AgentLoopState state,
                                                     String executionId, String agentId, String tenantId,
                                                     int round, long startMs, String conversationId, Exception e) {
        if (!modelInvoker.isRetryable(e)) return null;
        if (round < params.getMaxRounds() && !(round >= 3 && completionHandler.hasCompletionEvidence(chatMemory))) return null;

        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.MODEL_FAILURE_FALLBACK.getCode(), round, null,
                "模型在第 " + round + " 轮持续失败，尝试强制收敛完成当前节点", null, elapsed(startMs));
        try {
            ChatResponse forced = completionHandler.forceCompletion(resolution, systemPrompt, chatMemory,
                    params, executionId, agentId, tenantId, round + 1, startMs, agentLogService, executionEventStreamService);
            updateTokenUsage(state, forced);
            AiMessage forcedMsg = forced.aiMessage();
            String content = forcedMsg != null && StringUtils.hasText(forcedMsg.text())
                    ? forcedMsg.text()
                    : completionHandler.buildDegraded(ctx, chatMemory, round, e, null, params);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(AiMessage.from(content));
            state.setLastContent(content);
            return buildCompletedResult(executionId, agentId, tenantId, state, startMs, conversationId);
        } catch (Exception forceEx) {
            if (!completionHandler.hasCompletionEvidence(chatMemory)) return null;
            String degraded = completionHandler.buildDegraded(ctx, chatMemory, round, e, forceEx, params);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(AiMessage.from(degraded));
            state.setLastContent(degraded);
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.DEGRADED_COMPLETION.getCode(), round, null,
                    "模型调用与强制收敛均失败，基于已收集证据输出降级结论", null, elapsed(startMs));
            return buildCompletedResult(executionId, agentId, tenantId, state, startMs, conversationId);
        }
    }

    // =========================================================================
    //  结果构建方法
    // =========================================================================

    /** 构建正常完成结果，清洗输出并更新执行状态 */
    private AgentExecutionResult buildCompletedResult(String executionId, String agentId, String tenantId,
                                                       AgentLoopState state, long startMs, String conversationId) {
        String sanitized = qualityChecker.sanitize(state.getLastContent());
        state.setLastContent(sanitized);

        // 输出安全合规检查（PII、敏感信息、有害内容、合规风险）
        SecurityCheckDecisionVO securityDecision = evaluateOutputSecurity(agentId, executionId, tenantId, sanitized);
        if (securityDecision != null) {
            String decision = securityDecision.getDecision();
            String message = StringUtils.hasText(securityDecision.getMessage())
                    ? securityDecision.getMessage() : "输出内容命中安全策略";
            if (SecurityComplianceConstant.DECISION_BLOCK.equals(decision)) {
                agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, message,
                        state.getTotalTokenInput(), state.getTotalTokenOutput(), null);
                agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                        AgentLoopLogTypeEnum.SECURITY_BLOCKED.getCode(), state.getLastRound(), null,
                        "输出安全检查阻断: " + message, null, elapsed(startMs));
                executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.FAILED.getCode(),
                        state.getLastRound(), "输出安全检查阻断: " + message, startMs);
                return AgentExecutionResult.builder()
                        .status(STATUS_FAILED)
                        .errorMessage("输出安全检查阻断: " + message)
                        .tokenInput(state.getTotalTokenInput())
                        .tokenOutput(state.getTotalTokenOutput())
                        .rounds(state.getLastRound())
                        .build();
            }
            if (SecurityComplianceConstant.DECISION_PAUSE.equals(decision)) {
                agentLogService.updateExecutionStatus(executionId, STATUS_PAUSED, message,
                        state.getTotalTokenInput(), state.getTotalTokenOutput(), sanitized);
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.SECURITY_PAUSED.getCode(), state.getLastRound(), null,
                        "输出安全检查暂停: " + message, null, elapsed(startMs));
                executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.REQUIRE_INPUT.getCode(),
                        state.getLastRound(), "输出安全检查暂停: " + message, startMs);
                return AgentExecutionResult.builder()
                        .status(STATUS_PAUSED)
                        .outputResult(sanitized)
                        .errorMessage("输出安全检查暂停: " + message)
                        .conversationId(conversationId)
                        .tokenInput(state.getTotalTokenInput())
                        .tokenOutput(state.getTotalTokenOutput())
                        .rounds(state.getLastRound())
                        .build();
            }
            if (SecurityComplianceConstant.DECISION_WARN.equals(decision)) {
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.SECURITY_PAUSED.getCode(), state.getLastRound(), null,
                        "输出安全检查警告: " + message, null, elapsed(startMs));
            }
        }

        agentLogService.updateExecutionStatus(executionId, STATUS_COMPLETED, null,
                state.getTotalTokenInput(), state.getTotalTokenOutput(), sanitized);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_COMPLETED.getCode(), state.getLastRound(), null,
                "Agent 执行完成", null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.COMPLETED.getCode(),
                state.getLastRound(), "Agent 执行完成", startMs);

        // 异步触发记忆提取（执行完成后从对话历史中提取记忆）
        if (agentMemoryExtractionService != null && StringUtils.hasText(conversationId)) {
            List<ChatMessage> history = compositeChatMemoryStore.getMessages(conversationId);
            if (history != null && !history.isEmpty()) {
                agentMemoryExtractionService.extractMemoriesAsync(tenantId, agentId, executionId, history);
            }
        }

        // 异步从记忆中压缩更新 Agent 专属指令
        if (agentInstructionsAutoService != null) {
            agentInstructionsAutoService.updateFromMemoriesAsync(tenantId, agentId, executionId);
        }

        return AgentExecutionResult.builder()
                .status(STATUS_COMPLETED)
                .outputResult(sanitized)
                .conversationId(conversationId)
                .tokenInput(state.getTotalTokenInput())
                .tokenOutput(state.getTotalTokenOutput())
                .rounds(state.getLastRound())
                .build();
    }

    /** 构建失败结果并更新执行状态 */
    private AgentExecutionResult buildFailedResult(String executionId, String agentId, String tenantId,
                                                    int round, AgentLoopState state, long startMs, String errorMessage) {
        agentLogService.updateExecutionStatus(executionId, STATUS_FAILED, errorMessage,
                state.getTotalTokenInput(), state.getTotalTokenOutput(), null);
        agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                AgentLoopLogTypeEnum.EXECUTION_FAILED.getCode(), round, null, errorMessage, null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.FAILED.getCode(),
                round, errorMessage, startMs);
        return AgentExecutionResult.builder()
                .status(STATUS_FAILED)
                .errorMessage(errorMessage)
                .tokenInput(state.getTotalTokenInput())
                .tokenOutput(state.getTotalTokenOutput())
                .rounds(round)
                .build();
    }

    /** 构建用户停止结果并更新执行状态 */
    private AgentExecutionResult buildStoppedResult(String executionId, String agentId, String tenantId,
                                                     int round, long startMs) {
        agentLogService.updateExecutionStatus(executionId, STATUS_STOPPED, "用户已停止执行", null, null, null);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_STOPPED.getCode(), round, null, "用户已停止执行", null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.COMPLETED.getCode(),
                round, "用户已停止执行", startMs);
        return AgentExecutionResult.builder().status(STATUS_STOPPED).rounds(round).build();
    }

    /** 处理顶层致命异常 */
    private AgentExecutionResult handleFatalError(AgentExecutionContext ctx, Exception e, long startMs) {
        String errMsg = resolveExMsg(e);
        agentLogService.updateExecutionStatus(ctx.getExecutionId(), STATUS_FAILED, errMsg, null, null, null);
        executionEventStreamService.publishSimple(ctx.getExecutionId(), AgentExecutionEventTypeEnum.FAILED.getCode(),
                0, errMsg, startMs);
        return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage(errMsg).build();
    }

    // =========================================================================
    //  消息构建与日志辅助
    // =========================================================================

    /** 构建发送给模型的消息列表（SystemMessage + ChatMemory 历史） */
    private List<ChatMessage> buildMessages(String systemPrompt, ChatMemory chatMemory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.addAll(chatMemory.messages());
        return messages;
    }

    /** 记录每轮开始日志 */
    private void logRoundStart(String executionId, String agentId, String tenantId,
                                int round, ChatMemory chatMemory,
                                List<ToolSpecification> effectiveTools,
                                AgentLoopState state, long startMs) {
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.ROUND_START.getCode(), round, null,
                "轮次开始，history=" + chatMemory.messages().size()
                        + " msgs, effectiveTools=" + effectiveTools.size()
                        + ", qualityReflectionPending=" + state.isQualityReflectionPending(),
                null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.ROUND_START.getCode(),
                round, "第 " + round + " 轮开始", startMs);
    }

    /** 记录 AI 响应日志 */
    private void logAiResponse(String executionId, String agentId, String tenantId,
                                int round, ChatResponse response, String finishReason,
                                AgentEngineParams params, long startMs) {
        String preview = response.aiMessage() != null && response.aiMessage().text() != null
                ? response.aiMessage().text() : "";
        if (preview.length() > params.getLogContentLimit()) {
            preview = preview.substring(0, params.getLogContentLimit()) + "...[截断]";
        }
        int outputTokens = response.metadata().tokenUsage() != null
                ? (int) response.metadata().tokenUsage().outputTokenCount() : 0;
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.AI_RESPONSE.getCode(), round, null,
                preview + " [finishReason=" + finishReason + "]", outputTokens, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.AI_RESPONSE.getCode(),
                round, "已收到 AI 响应", startMs);
    }

    /** 记录 SystemPrompt 构建完成日志 */
    private void logSystemPromptBuilt(String executionId, String agentId, String tenantId,
                                      ContextInjector.PromptBuildResult promptBuildResult,
                                      String model, long startMs) {
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_START.getCode(), 0, null,
                "SystemPrompt 构建完成，model=" + model
                        + ", promptLen=" + promptBuildResult.totalChars()
                        + ", estimatedTokens=" + promptBuildResult.estimatedTokens()
                        + ", staticChars=" + promptBuildResult.staticChars()
                        + ", dynamicChars=" + promptBuildResult.dynamicChars()
                        + ", runtimeChars=" + promptBuildResult.runtimeChars()
                        + ", retrievalSource=" + promptBuildResult.retrievalSource()
                        + ", retrievalHits=" + promptBuildResult.retrievalHitCount(),
                null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.COMPLETED.getCode(),
                0, "Agent 执行启动", startMs);
    }

    /** 恢复执行时将人工输入追加到 ChatMemory */
    private void addResumeMessage(String executionId, String agentId, String tenantId,
                                   ChatMemory chatMemory, AgentExecutionInputDTO input, long startMs) {
        String content = StringUtils.hasText(input.getMessage()) ? input.getMessage() : "继续执行";
        chatMemory.add(UserMessage.from(content));
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_START.getCode(), 0, null,
                "恢复执行，追加人工输入: " + content, null, elapsed(startMs));
    }

    // =========================================================================
    //  上下文与模型链解析
    // =========================================================================

    /** 解析或生成 conversationId */
    private String resolveConversationId(AgentExecutionContext ctx) {
        return StringUtils.hasText(ctx.getConversationId()) ? ctx.getConversationId() : UUID.randomUUID().toString();
    }

    /** 将 conversationId 持久化到执行记录 */
    private void persistConversationId(String executionId, String conversationId) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setConversationId(conversationId);
        agentExecutionMapper.updateById(update);
    }

    /** 解析模型降级链 */
    private List<LangChain4jResolution> resolveModelChain(AgentExecutionContext ctx) {
        List<LangChain4jResolution> chain = aiModelRouter.resolveRouteChain(ctx.getModel());
        if (chain == null || chain.isEmpty()) {
            throw new IllegalStateException("无法解析模型配置: model=" + ctx.getModel());
        }
        return chain;
    }

    private void applyRuntimeInstructions(AgentExecutionContext ctx) {
        List<String> additionalContexts = new ArrayList<>(
                ctx.getAdditionalSystemContexts() == null ? List.of() : ctx.getAdditionalSystemContexts()
        );
        if (StringUtils.hasText(ctx.getReasoningStrength())) {
            switch (ctx.getReasoningStrength().trim().toLowerCase()) {
                case "high" -> additionalContexts.add("推理强度要求：请进行深度分析和推理，覆盖边界情况、风险与替代方案。");
                case "low" -> additionalContexts.add("推理强度要求：请直接给出简洁结论，不需要展开冗长推理。");
                default -> {
                    // medium 保持默认行为，不额外追加约束。
                }
            }
        }
        if (StringUtils.hasText(ctx.getOutputFormat())) {
            switch (ctx.getOutputFormat().trim().toLowerCase()) {
                case "markdown" -> additionalContexts.add("输出格式要求：请使用 Markdown 输出，适当使用标题、列表、表格和代码块。");
                case "plain_text" -> additionalContexts.add("输出格式要求：请使用纯文本输出，不要添加 Markdown 标记。");
                case "structured_json" ->
                        additionalContexts.add("输出格式要求：请仅输出单个 JSON 对象，不要输出代码块围栏或额外解释。");
                default -> {
                    // 未识别的格式保持兼容忽略。
                }
            }
        }
        if (StringUtils.hasText(ctx.getSkillCode())) {
            additionalContexts.add("技能偏好：用户指定本轮优先调用技能 `" + ctx.getSkillCode().trim() + "`，请优先选择与之匹配的工具。");
        }
        ctx.setAdditionalSystemContexts(additionalContexts);
    }

    /** 构建额外上下文字符串（inputPrompt + inputContext + 附件内容 合并） */
    private String buildExtraContext(AgentExecutionContext ctx) {
        String inputPrompt = ctx.getInputPrompt();
        Map<String, Object> inputContext = ctx.getInputContext();
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(inputPrompt)) sb.append(inputPrompt).append("\n\n");
        if (inputContext != null && !inputContext.isEmpty()) {
            inputContext.forEach((k, v) -> {
                if (v != null) sb.append(k).append(": ").append(v).append("\n");
            });
            sb.append("\n");
        }
        String attachmentContext = buildAttachmentContext(ctx.getAttachmentIds());
        if (StringUtils.hasText(attachmentContext)) {
            sb.append("附件内容摘要：\n").append(attachmentContext).append("\n");
        }
        return sb.toString().trim();
    }

    /** 构建用户消息内容 */
    private String buildUserMessage(AgentExecutionContext ctx) {
        return buildExtraContext(ctx);
    }

    private String buildAttachmentContext(List<String> attachmentIds) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return "";
        }
        List<String> contents = new ArrayList<>();
        for (String attachmentId : attachmentIds) {
            if (!StringUtils.hasText(attachmentId)) {
                continue;
            }
            String fileName = resolveAttachmentFileName(attachmentId);
            try (InputStream inputStream = documentStorageService.getObject(
                    documentStorageService.getDefaultBucket(), attachmentId)) {
                String content = fileContentExtractor.extract(fileName, inputStream);
                if (StringUtils.hasText(content)) {
                    contents.add("### " + fileName + "\n" + content);
                }
            } catch (Exception exception) {
                log.warn("读取附件内容失败: attachmentId={}, error={}", attachmentId, exception.getMessage());
            }
        }
        return String.join("\n\n", contents);
    }

    private String resolveAttachmentFileName(String attachmentId) {
        int index = attachmentId.lastIndexOf('/');
        return index >= 0 && index < attachmentId.length() - 1
                ? attachmentId.substring(index + 1)
                : attachmentId;
    }

    // =========================================================================
    //  状态检测与工具辅助
    // =========================================================================

    /** 检查执行是否已被用户停止 */
    private boolean isStopped(String executionId) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        return execution != null && STATUS_STOPPED.equals(execution.getStatus());
    }

    /** 更新 state 中的 token 用量 */
    private void updateTokenUsage(AgentLoopState state, ChatResponse response) {
        if (response == null || response.metadata() == null || response.metadata().tokenUsage() == null) return;
        state.addTokenUsage(
                response.metadata().tokenUsage().inputTokenCount(),
                response.metadata().tokenUsage().outputTokenCount());
    }

    /** 判断输出是否被 maxTokens 截断 */
    private boolean isOutputTruncated(String finishReason) {
        return "LENGTH".equalsIgnoreCase(finishReason) || "MAX_TOKENS".equalsIgnoreCase(finishReason);
    }

    /** 从工具结果列表中找到安全中断结果（BLOCK 优先于 PAUSE） */
    ToolResult findInterruptedToolResult(List<ToolResult> toolResults) {
        if (toolResults == null) return null;
        ToolResult paused = null;
        for (ToolResult r : toolResults) {
            if (r == null || !StringUtils.hasText(r.getControlAction())) continue;
            if (SecurityComplianceConstant.DECISION_BLOCK.equals(r.getControlAction())) return r;
            if (paused == null && SecurityComplianceConstant.DECISION_PAUSE.equals(r.getControlAction())) paused = r;
        }
        return paused;
    }

    /** 测试入口：处理安全中断工具结果 */
    AgentExecutionResult resolveInterruptedToolResult(AgentExecutionContext ctx,
                                                       String executionId, String agentId, String tenantId,
                                                       int round, List<ToolResult> toolResults,
                                                       long tokenInput, long tokenOutput,
                                                       String conversationId, long startMs) {
        AgentLoopState state = new AgentLoopState();
        state.addTokenUsage(tokenInput, tokenOutput);
        return resolveSecurityInterrupt(ctx, executionId, agentId, tenantId, round, toolResults, state, startMs, conversationId);
    }

    /** 测试入口：构建恢复执行的用户消息 */
    String buildResumeUserMessage(AgentExecutionInputDTO input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder("## 人工补充输入\n");
        if (StringUtils.hasText(input.getMessage())) sb.append(input.getMessage()).append("\n");
        if (input.getOptions() != null && !input.getOptions().isEmpty()) {
            sb.append("\n## 补充选项\n");
            input.getOptions().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
        }
        sb.append("\n不要重复已完成部分，继续执行剩余任务。");
        return sb.toString();
    }

    /** null 安全的 List 包装 */
    private <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    /** 计算自 startMs 以来的耗时毫秒数 */
    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private void logContextBudget(String executionId, String agentId, String tenantId,
                                  int round, String systemPrompt, ChatMemory chatMemory,
                                  List<ChatMessage> requestMessages, AiModelConfig modelConfig,
                                  AgentChatMemoryCompactor.CompactionResult compactionResult,
                                  AgentChatMemoryCompactor.NormalizationResult normalizationResult,
                                  long startMs) {
        int systemPromptTokens = tokenEstimatorSupport.estimateText(modelConfig, systemPrompt);
        int chatMemoryTokens = tokenEstimatorSupport.estimateMessages(modelConfig, chatMemory.messages());
        int requestTokens = tokenEstimatorSupport.estimateMessages(modelConfig, requestMessages);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                "CONTEXT_BUDGET", round, null,
                "systemPromptTokens=" + systemPromptTokens
                        + ", chatMemoryTokens=" + chatMemoryTokens
                        + ", requestTokens=" + requestTokens
                        + ", historyMessages=" + chatMemory.messages().size()
                        + ", requestMessages=" + requestMessages.size()
                        + ", compactionApplied=" + compactionResult.compacted()
                        + ", compactionBeforeTokens=" + compactionResult.beforeTokens()
                        + ", compactionAfterTokens=" + compactionResult.afterTokens()
                        + ", summaryChars=" + compactionResult.summaryChars()
                        + ", normalizationApplied=" + normalizationResult.normalized()
                        + ", normalizedSegments=" + normalizationResult.convertedSegments()
                        + ", droppedInvalidMessages=" + normalizationResult.droppedMessages(),
                null, elapsed(startMs));
    }

    /** 解析异常消息 */
    private String resolveExMsg(Exception e) {
        return e != null && StringUtils.hasText(e.getMessage()) ? e.getMessage()
                : e != null ? e.getClass().getSimpleName() : "unknown";
    }

    /** 评估 Agent 输出内容的安全合规性（PII、敏感信息、有害内容、合规风险） */
    private SecurityCheckDecisionVO evaluateOutputSecurity(String agentId, String executionId,
                                                           String tenantId, String content) {
        try {
            SecurityRuntimeGuardService guard = securityRuntimeGuardServiceProvider.getIfAvailable();
            if (guard == null || !StringUtils.hasText(content)) {
                return null;
            }
            SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
            request.setTenantId(tenantId);
            request.setScene(SecurityComplianceConstant.CHECK_SCENE_OUTPUT);
            request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
            request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION);
            request.setResourceId(executionId);
            request.setAgentId(agentId);
            request.setContent(content);
            SecurityCheckDecisionVO decision = guard.evaluate(request, null);
            if (decision == null || SecurityComplianceConstant.DECISION_ALLOW.equals(decision.getDecision())) {
                return null;
            }
            return decision;
        } catch (Exception e) {
            log.warn("输出安全检查异常，默认放行: executionId={}", executionId, e);
            return null;
        }
    }

    private void scheduleShadowReviewIfNeeded(AgentExecutionContext ctx,
                                               AgentLoopState state,
                                               AgentEngineParams params,
                                               String executionId, String agentId, String tenantId,
                                               int round, long startMs) {
        if (state.getPendingShadowReview() != null || !StringUtils.hasText(state.getLastContent())) {
            return;
        }
        var shadowReview = shadowReviewService.submitStructuralReview(
                ctx, state.getLastContent(), state.getQualityReflectionCount(), params
        );
        if (shadowReview == null) {
            return;
        }
        state.setPendingShadowReview(shadowReview);
        state.setPendingShadowReviewContent(state.getLastContent());
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.QUALITY_SHADOW_SUBMITTED.getCode(), round, null,
                "已提交结构化影子质量审核", null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId,
                AgentExecutionEventTypeEnum.QUALITY_SHADOW_SUBMITTED.getCode(),
                round, "已提交结构化影子质量审核", startMs);
    }

    private void consumePendingShadowReviewIfReady(ChatMemory chatMemory,
                                                    AgentLoopState state,
                                                    AgentEngineParams params,
                                                    String executionId, String agentId, String tenantId,
                                                    int round, long startMs) {
        consumePendingShadowReview(chatMemory, state, params, executionId, agentId, tenantId, round, startMs, false);
    }

    private QualityReflectionFeedback consumePendingShadowReview(ChatMemory chatMemory,
                                                                  AgentLoopState state,
                                                                  AgentEngineParams params,
                                                                  String executionId, String agentId, String tenantId,
                                                                  int round, long startMs,
                                                                  boolean awaitResult) {
        if (state.getPendingShadowReview() == null) {
            return null;
        }
        QualityReflectionFeedback feedback = awaitResult
                ? shadowReviewService.await(state.getPendingShadowReview(), params.getShadowQualityReviewAwaitMillis())
                : shadowReviewService.consumeIfDone(state.getPendingShadowReview());
        if (feedback == null) {
            if (state.getPendingShadowReview().isDone()) {
                clearPendingShadowReview(state);
            }
            return null;
        }
        applyQualityFeedback(chatMemory, state, feedback, executionId, agentId, tenantId, round, startMs, true);
        clearPendingShadowReview(state);
        return feedback;
    }

    private CompletionRevisionResult reviseWithPendingShadowReview(AgentExecutionContext ctx,
                                                                    LangChain4jResolution resolution,
                                                                    String systemPrompt,
                                                                    ChatMemory chatMemory,
                                                                    AgentLoopState state,
                                                                    AgentEngineParams params,
                                                                    String executionId, String agentId, String tenantId,
                                                                    long startMs) {
        QualityReflectionFeedback feedback = awaitPendingShadowReviewResult(state, params);
        if (feedback == null) {
            return CompletionRevisionResult.noop(state.getLastContent(), state.getLastRound());
        }
        clearPendingShadowReview(state);
        try {
            return completionHandler.reviseWithFeedback(
                    resolution, systemPrompt, chatMemory, feedback, state.getLastContent(), params,
                    executionId, agentId, tenantId, state.getLastRound() + 1, startMs,
                    agentLogService, executionEventStreamService
            );
        } catch (Exception e) {
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.QUALITY_GATE.getCode(), state.getLastRound(), null,
                    "影子质量审核回注失败，保留当前输出: " + resolveExMsg(e), null, elapsed(startMs));
            return CompletionRevisionResult.noop(state.getLastContent(), state.getLastRound());
        }
    }

    private QualityReflectionFeedback awaitPendingShadowReviewResult(AgentLoopState state, AgentEngineParams params) {
        if (state.getPendingShadowReview() == null) {
            return null;
        }
        QualityReflectionFeedback feedback = shadowReviewService.await(
                state.getPendingShadowReview(), params.getShadowQualityReviewAwaitMillis()
        );
        if (feedback != null || state.getPendingShadowReview().isDone()) {
            return feedback;
        }
        return null;
    }

    private void applyQualityFeedback(ChatMemory chatMemory,
                                       AgentLoopState state,
                                       QualityReflectionFeedback feedback,
                                       String executionId, String agentId, String tenantId,
                                       int round, long startMs,
                                       boolean fromShadowReview) {
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.QUALITY_FEEDBACK.getCode(), round, null, feedback.message(), null, elapsed(startMs));
        executionEventStreamService.publishWithPayload(executionId,
                fromShadowReview
                        ? AgentExecutionEventTypeEnum.QUALITY_SHADOW_APPLIED.getCode()
                        : AgentExecutionEventTypeEnum.QUALITY_GATE.getCode(),
                round, feedback.message(), feedback.payload(), startMs);
        if (chatMemory != null) {
            chatMemory.add(UserMessage.from(feedback.prompt()));
        }
        state.setQualityReflectionCount(state.getQualityReflectionCount() + 1);
        state.setQualityReflectionPending(true);
        state.setLoopCompleted(false);
    }

    private void clearPendingShadowReview(AgentLoopState state) {
        state.setPendingShadowReview(null);
        state.setPendingShadowReviewContent(null);
    }

    /** 校验执行上下文是否合法 */
    private boolean isValidContext(AgentExecutionContext ctx) {
        return ctx != null
                && StringUtils.hasText(ctx.getExecutionId())
                && StringUtils.hasText(ctx.getAgentId())
                && StringUtils.hasText(ctx.getTenantId());
    }

    /** 将结果包装为已完成的 CompletableFuture */
    private CompletableFuture<AgentExecutionResult> done(AgentExecutionResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
