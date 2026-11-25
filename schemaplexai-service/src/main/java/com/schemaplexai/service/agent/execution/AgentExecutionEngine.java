package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.AgentLoopPromptConstant;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.constant.SystemAgentPromptConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentLoopLogTypeEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionDecisionEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.PendingToolApproval;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.CompletionRevisionResult;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.AgentLoopToolHandler.ToolExecutionOutcome;
import com.schemaplexai.service.agent.execution.AgentModelInvoker.ModelCallResult;
import com.schemaplexai.service.agent.execution.approval.CreatePendingToolApprovalCommand;
import com.schemaplexai.service.agent.execution.approval.PendingToolApprovalService;
import com.schemaplexai.service.agent.execution.approval.ToolApprovalBatchService;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.model.entity.ToolApprovalBatch;
import com.schemaplexai.service.agent.execution.policy.ExecutionModePolicy;
import com.schemaplexai.service.agent.execution.policy.ExecutionModePolicyResolver;
import com.schemaplexai.service.agent.execution.policy.ExecutionModePromptContributor;
import com.schemaplexai.service.agent.execution.policy.ToolExecutionGate;
import com.schemaplexai.service.agent.execution.policy.ToolExecutionGateDecision;
import com.schemaplexai.service.agent.execution.policy.ToolExecutionGateRequest;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import com.schemaplexai.service.agent.tool.langchain4j.GuardedToolExecutor;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.hook.AgentHookContext;
import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.agent.hook.AgentHookType;
import com.schemaplexai.service.agent.memory.AgentMemoryExtractionService;
import com.schemaplexai.service.agent.memory.AgentInstructionsAutoService;
import com.schemaplexai.service.ai.MultimodalMessageBuilder;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import com.schemaplexai.service.memory.ArchivedChatMemory;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.stream.Collectors;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_CALL_ID;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_SOURCE_FAILURE;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;

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

    private static final Pattern INLINE_THINKING = Pattern.compile("(?is)<think(?:\\s[^>]*)?>.*?(?:</think>|\\z)");

    private static final String STATUS_PAUSED    = AgentExecutionStatusEnum.PAUSED.getCode();
    private static final String STATUS_QUEUED    = AgentExecutionStatusEnum.QUEUED.getCode();
    private static final String STATUS_RUNNING   = AgentExecutionStatusEnum.RUNNING.getCode();
    private static final String STATUS_STOPPED   = AgentExecutionStatusEnum.STOPPED.getCode();
    private static final String STATUS_COMPLETED = AgentExecutionStatusEnum.COMPLETED.getCode();
    private static final String STATUS_FAILED    = AgentExecutionStatusEnum.FAILED.getCode();
    private static final int DESKTOP_READ_STALL_ROUNDS = 3;
    private static final int DESKTOP_POST_EDIT_READ_ROUNDS = 2;
    private static final int DESKTOP_POST_EDIT_INSPECTION_ROUNDS = 3;
    private static final int DESKTOP_REMAINING_VERIFICATION_ROUNDS = 5;
    private static final int DESKTOP_FINAL_VERIFICATION_ROUNDS = 3;
    private static final int MAX_DESKTOP_READ_FINGERPRINTS = 128;
    private static final Set<String> DESKTOP_READ_ONLY_TOOLS = Set.of("sys_read", "sys_ls", "sys_stat", "sys_glob", "sys_grep");
    private static final Set<String> DESKTOP_FILE_CHANGE_TOOLS = Set.of("sys_write", "sys_edit");
    private static final String DESKTOP_VERIFICATION_PROGRESS = "核对用户要求和已有工具回执，优先完成缺少的改动与验证。"
            + "若实现和测试已保存，先运行构建或测试取得诊断。失败时集中处理相关错误，修复后立即重验。"
            + "若产物已构建，优先批量补齐工单要求的 CLI 等独立检查，复用仍有效的结果。"
            + "各项用户检查完成后再单独执行配置的最终验证命令。"
            + "最终命令成功后直接交付，若尚需进行其他 Shell 操作，之后必须重新取得最终验收回执。";

    /** 执行硬边界默认值（策略缺失时兜底，与 SandboxPolicyResolver 默认一致） */
    @Value("${schemaplexai.execution.default-max-minutes:30}")
    private int defaultMaxExecutionMinutes = 30;

    @Value("${schemaplexai.execution.default-max-token-budget:200000}")
    private long defaultMaxTokenBudget = 200_000L;

    @Value("${schemaplexai.agent.model.task-context-projection.enabled:false}")
    private boolean taskContextProjectionEnabled;

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
    private final AgentLoopDetectionService    loopDetectionService;
    private final ToolOutputMaskingService     toolOutputMaskingService;
    private final AgentTraceService            agentTraceService;
    private final AgentHookExecutor            hookExecutor;
    private final AgentContextBudgetService    agentContextBudgetService;
    private final MultimodalMessageBuilder     multimodalMessageBuilder;
    private final ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;
    private final ToolExecutionGate            toolExecutionGate;
    private final ExecutionModePolicyResolver  executionModePolicyResolver;
    private final ExecutionModePromptContributor executionModePromptContributor;
    private final PendingToolApprovalService   pendingToolApprovalService;
    private final ToolApprovalBatchService     toolApprovalBatchService;
    private final ExecutionSteeringService     executionSteeringService;
    private final AgentRunRecovery              runRecovery;
    private final TokenEstimatorSupport        tokenEstimatorSupport = new TokenEstimatorSupport();

    @Qualifier("agentExecutorPool")
    @Autowired
    private Executor agentExecutorPool;

    @Qualifier("toolParallelPool")
    @Autowired
    private Executor toolParallelPool;

    @Lazy
    @Autowired(required = false)
    private AgentMemoryExtractionService agentMemoryExtractionService;

    @Lazy
    @Autowired(required = false)
    private AgentInstructionsAutoService agentInstructionsAutoService;

    @Lazy
    @Autowired(required = false)
    private UserMemoryExtractionService userMemoryExtractionService;

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
            return done(AgentExecutionResult.builder().status(STATUS_FAILED).endReason(AgentLoopEndReason.INVALID_INPUT).errorMessage("执行上下文缺失").build());
        }
        return done(runClaimed(ctx, null, false));
    }

    @Async("evalAgentExecutorPool")
    public CompletableFuture<AgentExecutionResult> executeForEvaluation(AgentExecutionContext context) {
        return execute(context);
    }

    /** 异步恢复 Agent 执行，沿用人工审批契约。 */
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> resume(AgentExecutionContext ctx, AgentExecutionInputDTO input) {
        if (!isValidContext(ctx) || input == null) {
            return done(AgentExecutionResult.builder().status(STATUS_FAILED).endReason(AgentLoopEndReason.INVALID_INPUT)
                    .errorMessage("恢复执行上下文缺失").build());
        }
        if (pendingToolApprovalService.findActivePending(ctx.getExecutionId())
                .filter(pending -> !pending.getId().equals(input.getApprovalId())).isPresent()) {
            return done(currentExecutionResult(ctx.getExecutionId(), 0));
        }
        return done(runClaimed(ctx, input, false));
    }

    /** 在租约已失效时领取并恢复同一执行。 */
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> recover(AgentExecutionContext ctx) {
        return done(runClaimed(ctx, null, true));
    }

    private AgentExecutionResult runClaimed(AgentExecutionContext ctx, AgentExecutionInputDTO input, boolean recovering) {
        String executionId = ctx.getExecutionId();
        String expected = recovering ? STATUS_RUNNING : input == null ? STATUS_QUEUED : STATUS_PAUSED;
        boolean root = !StringUtils.hasText(ctx.getParentExecutionId());
        AgentRunRecovery.Lease lease = root ? runRecovery.claim(ctx, expected, recovering) : null;
        if ((root || recovering) && lease == null) {
            return currentExecutionResult(executionId, 0);
        }
        try (lease) {
            if (lease != null) {
                lease.bind();
            }
            ctx.setRecoveryLease(lease);
            ctx.setRecovering(recovering);
            long startMs = System.currentTimeMillis();
            AgentExecution previous = input != null || recovering ? agentExecutionMapper.selectById(executionId) : null;
            if (!recovering && !agentLogService.tryTransitionExecutionStatus(executionId, expected, STATUS_RUNNING,
                    null, null, null, null)) {
                return currentExecutionResult(executionId, 0);
            }
            AgentLoopState state = newLoopState(ctx, startMs, previous);
            try {
                if (recovering && !lease.checkpoint().recoverable()) {
                    state.setEndReason(AgentLoopEndReason.RECOVERY_REVIEW_REQUIRED);
                    return buildFailedResult(executionId, ctx.getAgentId(), ctx.getTenantId(), 0, state, startMs,
                            "执行在审批或初始化边界中断，需核对执行状态后重新发起任务");
                }
                if (recovering) {
                    state.setLastRound(lease.checkpoint().round());
                }
                return doExecute(ctx, input != null && input.isResumeBeforeFirstRound() ? null : input, startMs, state);
            } catch (AgentRunRecovery.LostOwnershipException e) {
                return currentExecutionResult(executionId, state.getLastRound());
            } catch (Exception e) {
                log.error("Agent 执行异常: executionId={}", executionId, e);
                recordBudgetFailureReason(state, e);
                return buildFailedResult(executionId, ctx.getAgentId(), ctx.getTenantId(),
                        state.getLastRound(), state, startMs, resolveExMsg(e));
            } finally {
                ctx.setRecoveryLease(null);
            }
        }
    }

    private void recordBudgetFailureReason(AgentLoopState state, Exception error) {
        if (error instanceof AgentExecutionBudget.BudgetExceededException) {
            state.setEndReason(state.getBudget().remainingMillis() == 0
                    ? AgentLoopEndReason.DEADLINE : AgentLoopEndReason.TOKEN_BUDGET);
        }
    }

    private AgentLoopState newLoopState(AgentExecutionContext ctx, long startMs, AgentExecution paused) {
        AgentLoopState state = new AgentLoopState();
        if (ctx.getAcceptance() != null) {
            state.setAcceptanceStatus(AgentAcceptance.Status.UNVERIFIED);
        }
        long usedMillis = 0L;
        if (paused != null) {
            state.addTokenUsage(tokenCount(paused.getTokenInput()), tokenCount(paused.getTokenOutput()));
            if (paused.getRuntimeElapsedMillis() != null) {
                usedMillis = Math.max(0L, paused.getRuntimeElapsedMillis());
            } else if (paused.getStartedAt() != null && paused.getCompletedAt() != null) {
                usedMillis = Math.max(0L, Duration.between(paused.getStartedAt(), paused.getCompletedAt()).toMillis());
            }
        }
        long deadline = startMs + Math.max(0L, resolveMaxExecutionMinutes(ctx) * 60_000L - usedMillis);
        if (ctx.getExecutionDeadlineEpochMillis() != null) {
            deadline = Math.min(deadline, ctx.getExecutionDeadlineEpochMillis());
        }
        if (ctx.getTeamBudget() != null) {
            deadline = Math.min(deadline, ctx.getTeamBudget().deadlineMs());
        }
        ctx.setExecutionDeadlineEpochMillis(deadline);
        state.setBudget(new AgentExecutionBudget(deadline, resolveMaxTokenBudget(ctx), usedMillis, startMs, state,
                () -> isStopped(ctx.getExecutionId()), ctx.getTeamBudget(), ctx.getRecoveryLease(), ctx.isEvaluation()));
        return state;
    }

    // =========================================================================
    //  编排层
    // =========================================================================

    /**
     * 核心编排：构建 SystemPrompt、初始化 ChatMemory、解析模型链，然后进入 Agentic Loop
     */
    private AgentExecutionResult doExecute(AgentExecutionContext ctx, AgentExecutionInputDTO resumeInput, long startMs,
                                           AgentLoopState state) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();
        state.getBudget().checkActive();
        String userId      = resolveUserId(ctx, executionId);
        ctx.setUserId(userId);

        // 加载执行参数（数据库配置优先，ctx 中的值次之，最后回退默认值）
        AgentEngineParams params = engineConfigLoader.load(agentId, ctx);
        applyRuntimeInstructions(ctx);

        // 解析模型链
        List<LangChain4jResolution> modelChain = resolveModelChain(ctx);
        LangChain4jResolution first = modelChain.getFirst();

        // 每次执行构建独立压缩器（compactionModel 执行级隔离，避免并发执行互相覆盖）
        AiModelConfig compactModelConfig = aiModelRouter.resolveCompactModel(tenantId);
        ChatModel compactModel = compactModelConfig != null ? aiModelRouter.buildChatModelForExecution(compactModelConfig) : null;
        AgentChatMemoryCompactor chatMemoryCompactor = new AgentChatMemoryCompactor(tokenEstimatorSupport);
        LangChain4jResolution compactResolution = compactModel == null
                ? null : new LangChain4jResolution(compactModel, compactModelConfig);

        // 首次执行时自动创建专属指令（幂等，非首次仅一次 SELECT）
        if (!ctx.isTemporaryChat() && agentInstructionsAutoService != null) {
            try {
                agentInstructionsAutoService.autoInitIfAbsent(tenantId, agentId);
            } catch (Exception e) {
                log.warn("自动初始化Agent专属指令失败: agentId={}, error={}", agentId, e.getMessage());
            }
        }

        // 构建 System Prompt（系统Agent注入执行模式指令）
        String extraContext  = buildExtraContext(ctx);
        List<String> systemContexts = mergeExecutionModeInstruction(ctx);
        ContextInjector.PromptBuildResult promptBuildResult = contextInjector.buildSystemPromptDetail(
                agentId, extraContext, tenantId, ctx.getTeamAgentId(), systemContexts, first.config(),
                userId, ctx.getProjectId(), ctx.isTemporaryChat(), ctx.getExecutionId());
        String systemPrompt  = promptBuildResult.prompt();
        logSystemPromptBuilt(executionId, agentId, tenantId, promptBuildResult, ctx.getModel(), startMs);
        persistContextBudgetSnapshot(ctx, promptBuildResult, first.config());

        // 初始化 ChatMemory
        String conversationId = resolveConversationId(ctx);
        persistConversationId(executionId, conversationId);
        ArchivedChatMemory chatMemory = chatMemoryCompactor.createChatMemory(
                conversationId, executionId, params, first.config(), compositeChatMemoryStore);

        // 添加用户消息（首次执行或恢复执行）
        TaskContextInput projectableInput = null;
        UserMessage initialUserMessage = null;
        if (ctx.isRecovering()) {
            AgentExecutionResult recoveryFailure = restoreToolRound(ctx, chatMemory, state, startMs);
            if (recoveryFailure != null) {
                return recoveryFailure;
            }
        } else if (resumeInput == null) {
            String authoredPrompt = StringUtils.hasText(ctx.getParentExecutionId())
                    ? null : ctx.getInputPrompt();
            UserMessage message = buildUserMessage(ctx);
            initialUserMessage = message;
            chatMemory.addUserInput(message, authoredPrompt);
            if (taskContextProjectionEnabled && !ctx.isTemporaryChat() && CollectionUtils.isEmpty(ctx.getAttachmentIds())
                    && promptBuildResult.taskContext() != null && canProjectTaskContext(ctx, authoredPrompt)) {
                projectableInput = new TaskContextInput(message, promptBuildResult.taskContext());
            }
        } else if (!isApprovalResume(resumeInput)) {
            addResumeMessage(executionId, agentId, tenantId, chatMemory, resumeInput, startMs);
        }
        log.info("ChatMemory 初始化完成: conversationId={}, 历史消息数={}", conversationId, chatMemory.messages().size());
        agentLogService.appendLog(executionId, agentId, tenantId, "DEBUG",
                AgentLoopLogTypeEnum.MODEL_RESOLVED.getCode(), 0, null,
                "chainSize=" + modelChain.size() + ", provider=" + first.config().getProvider()
                        + ", modelId=" + first.config().getModelId(), null, elapsed(startMs));

        return runAgenticLoop(ctx, systemPrompt, chatMemory, modelChain, params, startMs, conversationId, resumeInput,
                chatMemoryCompactor, compactResolution, state, projectableInput, initialUserMessage);
    }

    private boolean canProjectTaskContext(AgentExecutionContext context, String authoredPrompt) {
        if (StringUtils.hasText(authoredPrompt) && !StringUtils.hasText(context.getTeamAgentId())) {
            return true;
        }
        var projection = context.getTeamTaskContextProjection();
        return projection != null && projection.matches(context);
    }

    // =========================================================================
    //  Agentic Loop
    // =========================================================================

    /**
     * Agentic Loop 主循环：每轮调用 AI → 处理工具调用 → 检测收敛条件
     */
    private AgentExecutionResult runAgenticLoop(AgentExecutionContext ctx,
                                                 String systemPrompt,
                                                 ArchivedChatMemory chatMemory,
                                                 List<LangChain4jResolution> modelChain,
                                                 AgentEngineParams params,
                                                 long startMs,
                                                 String conversationId,
                                                 AgentExecutionInputDTO resumeInput,
                                                 AgentChatMemoryCompactor chatMemoryCompactor,
                                                 LangChain4jResolution compactResolution,
                                                 AgentLoopState state,
                                                 TaskContextInput projectableInput,
                                                 UserMessage initialUserMessage) {
        String executionId = ctx.getExecutionId();
        String agentId     = ctx.getAgentId();
        String tenantId    = ctx.getTenantId();
        int maxLoopRounds = ctx.isAcceptanceRepairOnly() ? 1 : params.getMaxRounds() + params.getMaxQualityReflections();
        AgentExecutionBudget budget = state.getBudget();
        boolean verifiedDesktop = StringUtils.hasText(ctx.getDesktopVerificationCommand())
                && DesktopExecutionTarget.from(ctx.getInputContext()) != null;
        boolean guideDesktopVerification = verifiedDesktop && shouldGuideDesktopVerification(ctx);
        boolean verificationCorrectionSent = false;
        int acceptanceRepairRound = 0;
        boolean verificationBudgetReminderSent = false;
        boolean finalVerificationBudgetReminderSent = false;
        long lastModelTokens = 0;

        if (isStopped(executionId)) {
            return buildStoppedResult(executionId, agentId, tenantId, 0, startMs);
        }
        if (!budget.canDispatch()) {
            return completeOnBudgetBoundary(ctx, chatMemory, state, params, startMs, conversationId, "执行预算已用尽");
        }
        try (AgentToolSessionFactory.AgentToolSession toolSession = agentToolSessionFactory.openSession(ctx, conversationId)) {
            String effectiveSystemPrompt = toolSession.augmentSystemPrompt(systemPrompt, params.getMaxToolCallsPerRound());
            SystemMessage canonicalSystem = SystemMessage.from(effectiveSystemPrompt);
            SystemMessage requestSystem = projectableInput == null ? canonicalSystem
                    : AgentModelRequestPlanner.markTaskContext(canonicalSystem, projectableInput.message(), projectableInput.range());
            AgentExecutionResult approvalResumeResult = consumePendingApprovalIfPresent(
                    ctx, toolSession, chatMemory, resumeInput, params, state, startMs, conversationId);
            if (approvalResumeResult != null) {
                return approvalResumeResult;
            }

            int firstRound = ctx.getRecoveryLease() == null ? 1 : Math.max(state.getLastRound(),
                    ctx.getRecoveryLease().checkpoint().round()) + 1;
            checkpoint(ctx, state, firstRound - 1);
            int lastAllowedRound = resumeInput != null ? firstRound + maxLoopRounds - 1 : maxLoopRounds;
            for (int round = firstRound; round <= lastAllowedRound; round++) {
                state.setLastRound(round);

                // 检查是否被用户停止
                if (isStopped(executionId)) {
                    return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
                }

                // 执行中用户引导（Steering）：轮边界按 seq 注入最新引导，追加消息不丢任务
                if (consumePendingSteeringIfAny(executionId, agentId, tenantId, conversationId,
                        chatMemory, round, startMs)) {
                    requestSystem = canonicalSystem;
                }

                // 执行截止时间硬边界（超时强制终止）
                if (!budget.canDispatch()) {
                    break;
                }

                // 准备本轮工具列表
                AgentToolSessionFactory.RoundToolContext roundCtx = toolSession.buildRoundContext(chatMemory, round);
                List<ToolSpecification> effectiveTools = state.isQualityReflectionPending() || ctx.isAcceptanceRepairOnly()
                        ? List.of() : nullSafe(roundCtx.toolServiceContext().effectiveTools());
                long remainingBeforeModel = budget.remainingTokens();
                String verificationReminder = state.isQualityReflectionPending() ? null : state.getDesktopVerificationReminder();
                if (verificationReminder != null) state.setDesktopVerificationReminder(null);
                boolean teamVerificationDeadline = ctx.isTeamDesktopVerificationOwner()
                        && round > firstRound && lastAllowedRound - round + 1 <= DESKTOP_FINAL_VERIFICATION_ROUNDS;
                boolean verificationUrgent = teamVerificationDeadline
                        || lastModelTokens > remainingBeforeModel / DESKTOP_FINAL_VERIFICATION_ROUNDS;
                if ((!verificationBudgetReminderSent || verificationUrgent && !finalVerificationBudgetReminderSent)
                        && guideDesktopVerification && !state.isQualityReflectionPending()
                        && roundCtx.isDesktopTool("sys_bash")
                        && (teamVerificationDeadline || state.isDesktopFileChanged()
                                && lastModelTokens > remainingBeforeModel / DESKTOP_REMAINING_VERIFICATION_ROUNDS)) {
                    boolean verificationCurrent = agentLogService.hasCurrentDesktopVerification(ctx);
                    if (!verificationCurrent || verificationUrgent) {
                        verificationBudgetReminderSent = true;
                        if (verificationUrgent) {
                            String urgentReminder = verificationCurrent
                                    ? "模型预算接近上限，现有最终验证回执有效。若工单检查均已覆盖，直接交付。"
                                            + "若仍缺工单明确要求的检查，将缺项集中在一轮，跳过重复检查和可选修改。"
                                            + "后续 Shell 会使现有最终验证回执失效，须保留一轮原样执行最终命令及一轮交付。"
                                    : "当前验收余下约三轮，集中完成必要检查，并留出一轮执行最终验证命令。";
                            verificationReminder = verificationReminder == null
                                    ? urgentReminder : verificationReminder + "\n" + urgentReminder;
                            finalVerificationBudgetReminderSent = true;
                        } else if (verificationReminder == null) {
                            verificationReminder = "按上一轮模型用量估算，剩余额度进入验证阶段。";
                        }
                    }
                }
                logRoundStart(executionId, agentId, tenantId, round, chatMemory, effectiveTools, state, startMs);
                String modelPhase = state.isQualityReflectionPending() ? "reflection" : "reasoning";
                state.setQualityReflectionPending(false);

                // 调用 AI 模型
                ModelCallResult callResult;
                int modelRound = round;
                var analysis = AgentExecutionActivity.start(AgentExecutionActivity.Kind.ANALYSIS,
                        "分析任务", "正在检查上下文", ctx.getTeamMemberRoleName(), round, Map.of());
                publishActivity(ctx, analysis);
                try {
                    AiModelConfig activeModelConfig = modelChain.getFirst().config();
                    // 工具输出遮蔽（在 compactIfNeeded 之前执行，减少 Token 占用）
                    if (toolOutputMaskingService.shouldMask(chatMemory)) {
                        ToolOutputMaskingService.MaskingResult maskResult =
                                toolOutputMaskingService.maskOldToolOutputs(chatMemory, 2);
                        if (maskResult.applied()) {
                            agentLogService.appendLog(executionId, agentId, tenantId, "DEBUG",
                                    AgentLoopLogTypeEnum.TOOL_OUTPUT_MASKED.getCode(), round, null,
                                    "工具输出遮蔽：压缩 " + maskResult.maskedCount() + " 条，节省 "
                                            + maskResult.savedChars() + " 字符", null, elapsed(startMs));
                        }
                    }
                    AgentChatMemoryCompactor.CompactionResult compactionResult =
                            chatMemoryCompactor.compactIfNeeded(chatMemory, params, activeModelConfig, isApprovalResume(resumeInput),
                                    verifiedDesktop, initialUserMessage,
                                    compactResolution == null ? null : request -> {
                                        logModelPhase(executionId, modelRound, "compaction");
                                        return modelInvoker.invokeWithRetry(compactResolution, request, params, executionId,
                                                agentId, tenantId, modelRound, startMs, agentLogService, budget, "compaction");
                                    }, budget.remainingTokens());
                    AgentChatMemoryCompactor.NormalizationResult normalizationResult =
                            chatMemoryCompactor.normalizeForRequest(chatMemory);
                    SystemMessage modelSystem = "reflection".equals(modelPhase) && ctx.getTeamTaskContextProjection() != null
                            ? canonicalSystem : requestSystem;
                    if (verificationReminder != null) {
                        // 编排提示仅附于本轮请求，保留任务消息边界和工具去重范围。
                        modelSystem = modelSystem.toBuilder().text(modelSystem.text() + "\n\n"
                                + verificationReminder + DESKTOP_VERIFICATION_PROGRESS).build();
                    }
                    if (verifiedDesktop && "reasoning".equals(modelPhase) && !isApprovalResume(resumeInput)) {
                        modelSystem = AgentModelRequestPlanner.markDesktopEvidenceProjection(modelSystem, initialUserMessage);
                    }
                    List<ChatMessage> messages = buildMessages(modelSystem, chatMemory);
                    logContextBudget(executionId, agentId, tenantId, round,
                            effectiveSystemPrompt, chatMemory, messages, activeModelConfig,
                            compactionResult, normalizationResult, startMs);
                    ChatRequest request = ChatRequest.builder().messages(messages).toolSpecifications(effectiveTools).build();
                    logModelPhase(executionId, round, modelPhase);
                    callResult = modelInvoker.invokeChainWithRetry(modelChain, request, params,
                            executionId, agentId, tenantId, round, startMs, agentLogService, budget,
                            ctx.isStream() ? progress -> executionEventStreamService.publishActivityProgress(
                                    activityExecutionId(ctx), analysis.progress(progress.summary())) : null);
                    lastModelTokens = remainingBeforeModel - budget.remainingTokens();
                } catch (Exception e) {
                    publishActivity(ctx, analysis.finish(false, "本轮模型请求未完成", null, Map.of()));
                    if (isStopped(executionId)) {
                        return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
                    }
                    if (e instanceof AgentRunRecovery.LostOwnershipException ownership) {
                        throw ownership;
                    }
                    if (e instanceof AgentExecutionBudget.BudgetExceededException) {
                        return completeOnBudgetBoundary(ctx, chatMemory, state, params, startMs, conversationId,
                                resolveExMsg(e));
                    }
                    if (e instanceof AgentModelInvoker.EmptyResponseException) {
                        state.setEndReason(AgentLoopEndReason.EMPTY_RESPONSE);
                        if (completionHandler.hasCompletionEvidence(chatMemory)) {
                            break;
                        }
                        return buildFailedResult(executionId, agentId, tenantId, round, state, startMs, resolveExMsg(e));
                    }
                    state.setEndReason(AgentLoopEndReason.MODEL_ERROR);
                    AgentExecutionResult graceful = handleModelFailure(ctx, modelChain.getLast(), effectiveSystemPrompt,
                            chatMemory, params, state, executionId, agentId, tenantId, round, startMs, conversationId, e);
                    if (graceful != null) return graceful;
                    return buildFailedResult(executionId, agentId, tenantId, round, state, startMs, resolveExMsg(e));
                }

                if (isStopped(executionId)) {
                    publishActivity(ctx, analysis.finish(false, "本轮模型请求已停止", null, Map.of()));
                    return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
                }
                // 处理 AI 响应
                ChatResponse response = callResult.response();
                publishActivity(ctx, finishAnalysis(analysis, response.aiMessage()));
                if (budget.remainingMillis() == 0 || budget.localRemainingTokens() < 0) {
                    break;
                }
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

                if (round == acceptanceRepairRound && !allRequests.isEmpty()) {
                    state.setAcceptanceStatus(AgentAcceptance.Status.REJECTED);
                    state.setEndReason(AgentLoopEndReason.ACCEPTANCE_REJECTED);
                    return buildFailedResult(executionId, agentId, tenantId, round, state, startMs,
                            "验收修订必须直接生成终稿");
                }

                // 循环检测（哈希 + 工具序列双层检测）
                AgentLoopDetectionService.LoopDetectionResult loopResult =
                        loopDetectionService.check(state, lastContent, allRequests);
                if (loopResult == AgentLoopDetectionService.LoopDetectionResult.CONFIRMED_LOOP) {
                    agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                            AgentLoopLogTypeEnum.LOOP_CONFIRMED.getCode(), round, null,
                            "循环检测确认死循环（哈希+工具序列双重告警），触发强制收敛", null, elapsed(startMs));
                    state.setEndReason(AgentLoopEndReason.REPEATED_LOOP);
                    break;
                }
                if (loopResult == AgentLoopDetectionService.LoopDetectionResult.WARNING) {
                    state.setLoopWarningCount(state.getLoopWarningCount() + 1);
                    agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                            AgentLoopLogTypeEnum.LOOP_WARNING.getCode(), round, null,
                            "循环检测告警（单层），累计告警次数=" + state.getLoopWarningCount(), null, elapsed(startMs));
                    // WARNING 累积超过阈值也触发强制收敛
                    if (state.getLoopWarningCount() >= params.getMaxLoopWarnings()) {
                        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                                AgentLoopLogTypeEnum.LOOP_CONFIRMED.getCode(), round, null,
                                "循环检测告警累积达到阈值(" + params.getMaxLoopWarnings() + ")，触发强制收敛",
                                null, elapsed(startMs));
                        state.setEndReason(AgentLoopEndReason.REPEATED_LOOP);
                        break;
                    }
                }

                // 无工具调用分支
                if (allRequests.isEmpty()) {
                    if (!verificationCorrectionSent && guideDesktopVerification && round < lastAllowedRound
                            && !isOutputTruncated(finishReason) && StringUtils.hasText(lastContent)
                            && roundCtx.isDesktopTool("sys_bash") && budget.canDispatch()
                            && !agentLogService.hasCurrentDesktopVerification(ctx)) {
                        // 保留生成的候选结论，给模型一次在原权限和预算内补齐验证的机会。
                        AgentExecutionResult securityInterrupt = validateCandidateOutput(ctx, state, startMs, conversationId);
                        if (securityInterrupt != null) return securityInterrupt;
                        chatMemory.add(aiMsg);
                        chatMemory.add(UserMessage.from("当前工作区缺少有效的最终验证回执。核对已有工具结果，"
                                + "完成尚缺的任务检查后，单独在工作区根目录执行已配置的验证命令，再按实际回执交付。"));
                        verificationCorrectionSent = true;
                        continue;
                    }
                    if (ctx.getAcceptance() != null && ctx.getAcceptance().canRepairWithModel()
                            && acceptanceRepairRound == 0 && round < lastAllowedRound && budget.canDispatch()
                            && !isOutputTruncated(finishReason) && StringUtils.hasText(lastContent)) {
                        AgentAcceptance.Verdict verdict = ctx.getAcceptance().evaluate(qualityChecker.sanitize(lastContent), false);
                        if (!verdict.passed()) {
                            AgentExecutionResult interrupt = validateCandidateOutput(ctx, state, startMs, conversationId);
                            if (interrupt != null) return interrupt;
                            chatMemory.add(aiMsg);
                            chatMemory.add(UserMessage.from("只修复以下验收问题，保留其余内容。" + verdict.explanation()));
                            state.setQualityReflectionPending(true);
                            acceptanceRepairRound = round + 1;
                            continue;
                        }
                    }
                    AgentExecutionResult r = handleNoToolCall(ctx, chatMemory, aiMsg, state, params,
                            executionId, agentId, tenantId, round, finishReason, startMs, conversationId);
                    if (r != null) return r;
                    if (state.isLoopCompleted() || state.getEndReason() != null) break;
                    continue;
                }

                // 有工具调用但全部被拒绝
                if (executableRequests.isEmpty()) {
                    AgentExecutionResult r = handleUnexpectedToolCall(chatMemory, aiMsg, state, params,
                            executionId, agentId, tenantId, round, startMs, conversationId);
                    if (r != null) return r;
                    if (state.isLoopCompleted() || state.getEndReason() != null) break;
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

        if (ctx.isAcceptanceRepairOnly() && !state.isLoopCompleted()) {
            return buildFailedResult(executionId, agentId, tenantId, state.getLastRound(), state, startMs,
                    "定向验收修订未生成有效候选");
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

        if (StringUtils.hasText(state.getLastContent())) {
            AgentExecutionResult securityInterrupt = validateCandidateOutput(ctx, state, startMs, conversationId);
            if (securityInterrupt != null) {
                return securityInterrupt;
            }
        }

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
            state.setEndReason(AgentLoopEndReason.EMPTY_RESPONSE);
            return null; // 由 finalizeLoop 处理
        }

        // 空响应且无历史证据 → 直接失败
        if (!StringUtils.hasText(state.getLastContent())) {
            state.setEndReason(AgentLoopEndReason.EMPTY_RESPONSE);
            return buildFailedResult(executionId, agentId, tenantId, round, state, startMs,
                    AgentLoopPromptConstant.EMPTY_AI_RESPONSE_ERROR);
        }

        if (ctx.isAcceptanceRepairOnly()) {
            chatMemory.add(aiMsg);
            state.setLoopCompleted(true);
            return null;
        }

        if (state.getLastContent().equals(state.getLastReflectedContent())) {
            chatMemory.add(aiMsg);
            state.setEndReason(AgentLoopEndReason.QUALITY_LIMIT);
            state.setLoopCompleted(true);
            return null;
        }

        if (!state.getBudget().canDispatch() || state.getQualityReflectionCount() >= params.getMaxQualityReflections()) {
            chatMemory.add(aiMsg);
            state.setLoopCompleted(true);
            return null;
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
                executionId, agentId, tenantId, round, startMs);
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
            state.setEndReason(AgentLoopEndReason.UNEXPECTED_TOOL);
            state.setLoopCompleted(true);
            return null;
        }
        if (completionHandler.hasCompletionEvidence(chatMemory)) {
            state.setEndReason(AgentLoopEndReason.UNEXPECTED_TOOL);
            return null;
        }
        if (state.getUnexpectedToolCallRecoveries() >= params.getMaxUnexpectedToolCallRecoveries()) {
            state.setEndReason(AgentLoopEndReason.UNEXPECTED_TOOL);
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
        if (isStopped(executionId)) {
            return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
        }
        List<com.schemaplexai.service.agent.tool.model.ToolCall> toolCalls = toolHandler.toToolCalls(executableRequests);
        List<com.schemaplexai.service.agent.tool.model.ToolCall> limited = toolHandler.limitToolCalls(
                executionId, agentId, tenantId, round, toolCalls, params.getMaxToolCallsPerRound(), agentLogService, startMs);
        executionEventStreamService.publishSimple(executionId, "TOOL_CALL", round,
                "开始执行工具调用(" + limited.size() + "/" + toolCalls.size() + "个)", startMs);
        chatMemory.add(aiMsg);
        // 模型消息已落盘，工具可以在此边界以后领取。
        checkpoint(ctx, state, round);

        List<ToolResult> toolResults = new ArrayList<>();
        int execCount = limited.size();
        AgentLoopToolHandler.ToolResultCompressionOptions compressionOptions =
                new AgentLoopToolHandler.ToolResultCompressionOptions(
                        params.getMaxToolResultMessageLength(),
                        params.getToolRequestSummaryLimit()
                );

        // 并行执行允许的工具（读工具并行，写工具串行）
        List<ToolExecutionRequest> executableSubList = executableRequests.subList(0, execCount);
        // 触发 BEFORE_TOOL_EXECUTE Hook（逐个检查，任一阻止则跳过该工具）
        List<ToolExecutionRequest> approvedRequests = new ArrayList<>();
        List<ToolExecutionRequest> suggestedRequests = new ArrayList<>();
        Map<String, String> callStatuses = new LinkedHashMap<>();
        rejectedRequests.forEach(req -> callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_REJECTED));
        executableRequests.subList(execCount, executableRequests.size())
                .forEach(req -> callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_LIMITED));
        Map<String, Object> approvalPayload = null;
        ToolExecutionRequest pausedForApproval = null;
        for (ToolExecutionRequest req : executableSubList) {
            AgentHookContext beforeCtx = AgentHookContext.builder()
                    .executionId(executionId).agentId(agentId).tenantId(tenantId)
                    .round(round).toolRequest(req).toolCode(req.name()).build();
            if (!hookExecutor.fire(AgentHookType.BEFORE_TOOL_EXECUTE, beforeCtx, agentId)) {
                callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_REJECTED);
                ToolResult rejected = toolHandler.buildRejectedResult(req);
                toolResults.add(rejected);
                chatMemory.add(toolHandler.buildResultMessage(req, rejected, Map.of(), true, null, compressionOptions));
                continue;
            }
            // 执行模式拦截
            ToolIoTypeEnum ioType = roundCtx.ioTypeIndex().getOrDefault(req.name(), ToolIoTypeEnum.READ_WRITE);
            ToolExecutionGateDecision decision = toolExecutionGate.evaluate(ToolExecutionGateRequest.builder()
                    .tenantId(tenantId)
                    .agentId(agentId)
                    .executionId(executionId)
                    .conversationId(ctx.getConversationId())
                    .round(round)
                    .policy(resolveExecutionModePolicy(ctx))
                    .toolRequest(req)
                    .ioType(ioType)
                    .systemAgent(ctx.isSystemAgent())
                    .desktopTool(roundCtx.isDesktopTool(req.name()))
                    .build());
            if (decision.getDecision() == ToolExecutionDecisionEnum.SUGGEST_ONLY) {
                // 建议模式：仅发送建议事件，不实际执行
                Map<String, Object> previewPayload = buildSuggestPreviewPayload(req);
                executionEventStreamService.publishWithPayload(executionId,
                        AgentExecutionEventTypeEnum.SUGGESTION.getCode(), round,
                        "建议执行: " + req.name(),
                        previewPayload,
                        startMs);
                suggestedRequests.add(req);
                callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_SUGGESTED);
                continue;
            }
            if (decision.getDecision() == ToolExecutionDecisionEnum.PAUSE_APPROVAL) {
                if (ctx.getRecoveryLease() != null) {
                    ctx.getRecoveryLease().requireReview();
                }
                callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL);
                ToolApprovalBatch batch = toolApprovalBatchService.createBatch(
                        tenantId, agentId, executionId, conversationId, round, aiMsg.toolExecutionRequests(), callStatuses);
                PendingToolApproval approval = createPendingApprovalForTool(ctx, req, ioType, round, conversationId, batch.getId());
                toolApprovalBatchService.linkApproval(batch, req.id(), approval.getId());
                approvalPayload = buildApprovalRequiredPayload(approval, req, ioType, ctx.getExecutionMode());
                pausedForApproval = req;
                break;
            }
            if (decision.getDecision() == ToolExecutionDecisionEnum.DENY) {
                callStatuses.put(req.id(), ToolApprovalBatchService.CALL_STATUS_REJECTED);
                ToolResult rejectedResult = toolHandler.buildRejectedResult(req);
                toolResults.add(rejectedResult);
                chatMemory.add(toolHandler.buildResultMessage(req, rejectedResult, Map.of(), true, null, compressionOptions));
                executionEventStreamService.publishWithPayload(executionId,
                        AgentExecutionEventTypeEnum.TOOL_RESULT.getCode(), round,
                        req.name() + " 被执行策略拒绝",
                        Map.of("toolCode", req.name(), "reason", decision.getReason()),
                        startMs);
                continue;
            }
            // 直接执行
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.TOOL_START.getCode(), round,
                    req.name() + " 开始执行",
                    Map.of("toolCode", req.name(), "args", req.arguments() != null ? req.arguments() : "{}"),
                    startMs);
            approvedRequests.add(req);
        }
        // 建议模式：返回建议信息，不实际执行
        if (pausedForApproval == null) {
            for (ToolExecutionRequest suggested : suggestedRequests) {
                String suggestMsg = "[建议模式] 工具 " + suggested.name() + " 未执行，仅作为建议展示。参数: " + suggested.arguments();
                ToolResult suggestResult = ToolResult.builder()
                        .toolCode(suggested.name()).success(true)
                        .result(objectMapper.valueToTree(buildSuggestPreviewPayload(suggested, suggestMsg)))
                        .build();
                toolResults.add(suggestResult);
                chatMemory.add(toolHandler.buildResultMessage(suggested, suggestResult, Map.of(), true, null, compressionOptions));
            }
        }
        // 需要审批：暂停执行
        if (pausedForApproval != null) {
            if (!pauseExecution(executionId, null, state, null)) {
                return currentExecutionResult(executionId, round);
            }
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.APPROVAL_REQUIRED.getCode(), round,
                    "工具 " + pausedForApproval.name() + " 需要审批", approvalPayload, startMs);
            recordAgentTrace(executionId, tenantId, agentId, startMs,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_PAUSED);
            logLoopOutcome(executionId, STATUS_PAUSED, AgentLoopEndReason.APPROVAL_REQUIRED, false);
            return AgentExecutionResult.builder()
                    .status(STATUS_PAUSED).endReason(AgentLoopEndReason.APPROVAL_REQUIRED)
                    .outputResult("等待用户审批: " + pausedForApproval.name())
                    .conversationId(conversationId).rounds(round)
                    .tokenInput(state.getTotalTokenInput()).tokenOutput(state.getTotalTokenOutput())
                    .build();
        }
        // 发送并行工具组开始事件（在执行前发送）
        boolean isParallel = params.isParallelToolExecution() && approvedRequests.size() > 1;
        if (isParallel) {
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.TOOL_PARALLEL_START.getCode(), round,
                    "并行执行 " + approvedRequests.size() + " 个工具",
                    Map.of("toolCount", approvedRequests.size(),
                           "tools", approvedRequests.stream().map(ToolExecutionRequest::name).toList()),
                    startMs);
        }
        List<ToolExecutionOutcome> outcomes = toolHandler.executeBatch(
                approvedRequests, compressionOptions, isParallel ? toolParallelPool : null,
                params.getMaxParallelTools(), roundCtx.ioTypeIndex(), state.getBudget()::canDispatch,
                request -> executeTrackedTool(ctx, roundCtx, request, compressionOptions, round));
        // 发送并行工具组完成事件
        if (isParallel) {
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.TOOL_PARALLEL_END.getCode(), round,
                    "并行工具组执行完成",
                    Map.of("toolCount", approvedRequests.size(), "elapsedMs", elapsed(startMs)),
                    startMs);
        }
        for (int i = 0; i < outcomes.size(); i++) {
            ToolExecutionOutcome outcome = outcomes.get(i);
            toolResults.add(outcome.toolResult());
            chatMemory.add(outcome.resultMessage());
            // 发送 TOOL_END 事件
            ToolExecutionRequest req = approvedRequests.get(i);
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.TOOL_END.getCode(), round,
                    req.name() + (outcome.wasDispatched() ? " 执行完成" : " 未执行"),
                    Map.of("toolCode", req.name(), "success", outcome.toolResult().isSuccess(),
                            "executed", outcome.wasDispatched()),
                    startMs);
            if (!outcome.wasDispatched()) {
                continue;
            }
            agentTraceService.recordToolSpan(
                    executionId,
                    tenantId,
                    agentId,
                    req.name(),
                    outcome.startedAt(),
                    outcome.durationMs(),
                    outcome.toolResult().isSuccess() ? "SUCCESS" : "FAILED"
            );
            // 触发 AFTER_TOOL_EXECUTE Hook
            AgentHookContext afterCtx = AgentHookContext.builder()
                    .executionId(executionId).agentId(agentId).tenantId(tenantId)
                    .round(round).toolRequest(approvedRequests.get(i))
                    .toolCode(approvedRequests.get(i).name()).toolResult(outcome.toolResult()).build();
            hookExecutor.fire(AgentHookType.AFTER_TOOL_EXECUTE, afterCtx, agentId);
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

        if (findInterruptedToolResult(toolResults) == null && shouldGuideDesktopVerification(ctx)
                && DesktopExecutionTarget.from(ctx.getInputContext()) != null) {
            observeDesktopReadProgress(state, roundCtx, approvedRequests, outcomes);
            if (roundCtx.isDesktopTool("sys_bash") && outcomes.stream().anyMatch(this::hasDesktopMavenSourceFailure)) {
                state.setDesktopVerificationReminder("本机 Maven 回执包含带文件行号的错误。先关联同一文件的全部诊断，"
                        + "复用已经读到的源码，只补读需要的区段。将同一文件的相关修复合成一次编辑，"
                        + "在一轮内提交可以独立执行的文件修复。修复后直接运行需要的构建，"
                        + "再补做缺少的工单检查，最后单独执行最终验收命令。");
            }
        }
        return resolveSecurityInterrupt(ctx, executionId, agentId, tenantId, round,
                toolResults, state, startMs, conversationId);
    }

    private boolean hasDesktopMavenSourceFailure(ToolExecutionOutcome outcome) {
        ToolResult result = outcome.toolResult();
        var attributes = outcome.resultMessage().attributes();
        return outcome.wasDispatched() && !result.isSuccess() && "sys.bash".equals(result.getToolCode())
                && result.getDesktopCallId() != null
                && result.getDesktopCallId().toString().equals(attributes.get(DESKTOP_CALL_ID))
                && Boolean.TRUE.equals(attributes.get(DESKTOP_MAVEN_SOURCE_FAILURE));
    }

    private void observeDesktopReadProgress(AgentLoopState state,
                                            AgentToolSessionFactory.RoundToolContext roundContext,
                                            List<ToolExecutionRequest> requests, List<ToolExecutionOutcome> outcomes) {
        var seen = state.getDesktopReadFingerprints();
        if (requests.stream().anyMatch(request -> !DESKTOP_READ_ONLY_TOOLS.contains(request.name())
                || !roundContext.isDesktopTool(request.name()))) {
            for (int index = 0; index < outcomes.size(); index++) {
                ToolExecutionRequest request = requests.get(index);
                ToolExecutionOutcome outcome = outcomes.get(index);
                if (DESKTOP_FILE_CHANGE_TOOLS.contains(request.name()) && roundContext.isDesktopTool(request.name())
                        && outcome.wasDispatched() && outcome.toolResult().isSuccess()
                        && outcome.toolResult().getDesktopCallId() != null) {
                    state.setDesktopFileChanged(true);
                }
            }
            seen.clear();
            state.setRepeatedDesktopReadRounds(0);
            state.setConsecutiveDesktopReadRounds(0);
            return;
        }
        if (state.isDesktopReadReminderSent()) return;
        if (requests.isEmpty() || outcomes.size() != requests.size()
                || outcomes.stream().anyMatch(outcome -> !outcome.wasDispatched()
                        || !outcome.toolResult().isSuccess() || outcome.toolResult().getDesktopCallId() == null
                        || outcome.toolResult().getResult() == null || !outcome.toolResult().getResult().isTextual()
                        || outcome.toolResult().getResult().textValue().endsWith("\n[已截断]")
                        || !(outcome.resultMessage().attributes().get(DESKTOP_READ_EVIDENCE_HASH) instanceof String))) {
            state.setRepeatedDesktopReadRounds(0);
            state.setConsecutiveDesktopReadRounds(0);
            return;
        }
        var fingerprints = outcomes.stream()
                .map(outcome -> (String) outcome.resultMessage().attributes().get(DESKTOP_READ_EVIDENCE_HASH)).toList();
        int consecutiveRounds = state.getConsecutiveDesktopReadRounds() + 1;
        state.setConsecutiveDesktopReadRounds(consecutiveRounds);
        int repeatedRounds = seen.containsAll(fingerprints) ? state.getRepeatedDesktopReadRounds() + 1 : 0;
        state.setRepeatedDesktopReadRounds(repeatedRounds);
        boolean stalledAfterEdit = state.isDesktopFileChanged() && consecutiveRounds >= DESKTOP_POST_EDIT_READ_ROUNDS
                && fingerprints.stream().anyMatch(seen::contains);
        if (seen.size() + fingerprints.size() > MAX_DESKTOP_READ_FINGERPRINTS) seen.clear();
        seen.addAll(fingerprints);
        boolean awaitingProgressAfterEdit = state.isDesktopFileChanged()
                && consecutiveRounds >= DESKTOP_POST_EDIT_INSPECTION_ROUNDS;
        if (repeatedRounds == DESKTOP_READ_STALL_ROUNDS || stalledAfterEdit || awaitingProgressAfterEdit) {
            state.setDesktopReadReminderSent(true);
            String progress = stalledAfterEdit || repeatedRounds == DESKTOP_READ_STALL_ROUNDS
                    ? "近期连续读取已核对的相同文件版本。复用已有读取，仅补读缺少证据或可能已变化的文件。"
                    : "本机文件修改后已连续多轮读取，尚未推进修复或构建。根据现有证据完成具体修改或执行验证。";
            state.setDesktopVerificationReminder(progress);
        }
    }

    private AgentExecutionResult consumePendingApprovalIfPresent(AgentExecutionContext ctx,
                                                                 AgentToolSessionFactory.AgentToolSession toolSession,
                                                                 ChatMemory chatMemory,
                                                                 AgentExecutionInputDTO input,
                                                                 AgentEngineParams params,
                                                                 AgentLoopState state,
                                                                 long startMs,
                                                                 String conversationId) {
        if (!isApprovalResume(input)) {
            return null;
        }
        String executionId = ctx.getExecutionId();
        String agentId = ctx.getAgentId();
        String tenantId = ctx.getTenantId();
        if (!StringUtils.hasText(input.getApprovalId())) {
            log.warn("审批恢复缺少 approvalId，降级为普通人工输入: executionId={}", executionId);
            addResumeMessage(executionId, agentId, tenantId, chatMemory, input, startMs);
            return null;
        }
        Optional<PendingToolApproval> approvalOpt = pendingToolApprovalService.findById(input.getApprovalId());
        if (approvalOpt.isEmpty()) {
            log.warn("审批恢复未找到待审批记录，降级为普通人工输入: executionId={}, approvalId={}",
                    executionId, input.getApprovalId());
            addResumeMessage(executionId, agentId, tenantId, chatMemory, input, startMs);
            return null;
        }
        PendingToolApproval approval = approvalOpt.get();
        if (!tenantId.equals(approval.getTenantId()) || !executionId.equals(approval.getExecutionId())
                || !agentId.equals(approval.getAgentId()) || !conversationId.equals(approval.getConversationId())) {
            log.warn("审批归属校验失败，拒绝恢复: executionId={}, approvalId={}", executionId, input.getApprovalId());
            return buildFailedResult(executionId, agentId, tenantId,
                    approval.getRoundNum() != null ? approval.getRoundNum() : 0, state, startMs,
                    "审批归属校验失败，拒绝恢复该审批");
        }
        int round = approval.getRoundNum() != null ? approval.getRoundNum() : 0;
        AgentToolSessionFactory.RoundToolContext roundCtx = toolSession.buildRoundContext(chatMemory, round);
        AgentExecutionResult interrupted = applyPendingApprovalDecision(
                ctx, roundCtx, chatMemory, input, approval, params, startMs, conversationId, state);
        if (interrupted == null && StringUtils.hasText(input.getMessage())) {
            addResumeMessage(executionId, agentId, tenantId, chatMemory, input, startMs);
        }
        return interrupted;
    }

    AgentExecutionResult applyPendingApprovalDecision(AgentExecutionContext ctx,
                                                      AgentToolSessionFactory.RoundToolContext roundCtx,
                                                      ChatMemory chatMemory,
                                                      AgentExecutionInputDTO input,
                                                      PendingToolApproval approval,
                                                      AgentEngineParams params,
                                                      long startMs,
                                                      String conversationId,
                                                      AgentLoopState state) {
        if (approval == null) {
            return null;
        }
        ToolApprovalBatch batch = toolApprovalBatchService.findForApproval(
                approval.getBatchId(), ctx.getExecutionId(), ctx.getTenantId());
        if (toolApprovalBatchService.isResumable(batch)) {
            return resumeApprovalBatch(ctx, roundCtx, chatMemory, approval, batch, params, state, startMs, conversationId);
        }
        String executionId = ctx.getExecutionId();
        String agentId = ctx.getAgentId();
        String tenantId = ctx.getTenantId();
        int round = approval.getRoundNum() != null ? approval.getRoundNum() : 0;
        ToolExecutionRequest request = pendingToolApprovalService.rebuildToolRequest(approval);
        AgentLoopToolHandler.ToolResultCompressionOptions compressionOptions =
                new AgentLoopToolHandler.ToolResultCompressionOptions(
                        params.getMaxToolResultMessageLength(),
                        params.getToolRequestSummaryLimit()
                );

        if (pendingToolApprovalService.isApproved(approval)) {
            if (isStopped(executionId)) {
                return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
            }
            if (state.getBudget() != null) {
                state.getBudget().checkActive();
            }
            // 一次性消费（CAS approved/edited→consumed）：重复恢复同 approvalId 不得重复执行写工具
            if (!pendingToolApprovalService.consume(approval.getId())) {
                log.warn("审批已消费，跳过重复执行（幂等保护）: approvalId={}", approval.getId());
                ToolResult alreadyConsumed = buildApprovalConsumedResult(request, approval);
                chatMemory.add(toolHandler.buildResultMessage(
                        request, alreadyConsumed, Map.of("approvalId", approval.getId()), true,
                        null, compressionOptions));
                fillLegacyApprovalResults(chatMemory, request, compressionOptions);
                publishApprovalResumeEvent(executionId, round, approval, false, false, startMs);
                return null;
            }
            if (isStopped(executionId)) {
                return buildStoppedResult(executionId, agentId, tenantId, round, startMs);
            }
            if (state.getBudget() != null) {
                state.getBudget().checkActive();
            }
            ToolExecutionOutcome outcome = executeWithActivity(ctx, roundCtx, request, compressionOptions, round);
            chatMemory.add(outcome.resultMessage());
            // 批次账本：当前 callId 标记 consumed，其余未决策 callId 显式记账（restored_skipped）
            toolApprovalBatchService.markCallStatus(
                    approval.getBatchId(), approval.getToolCallId(), ToolApprovalBatchService.CALL_STATUS_CONSUMED);
            toolApprovalBatchService.markOthersSkippedAfterRestore(approval.getBatchId(), approval.getToolCallId());
            fillLegacyApprovalResults(chatMemory, request, compressionOptions);
            publishApprovalResumeEvent(executionId, round, approval, outcome.toolResult().isSuccess(), true, startMs);
            agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                    AgentLoopLogTypeEnum.TOOL_RESULT.getCode(), round, request.name(),
                    "审批通过后执行待审批工具，approvalId=" + approval.getId(), null, elapsed(startMs));
            agentTraceService.recordToolSpan(
                    executionId,
                    tenantId,
                    agentId,
                    request.name(),
                    outcome.startedAt(),
                    outcome.durationMs(),
                    outcome.toolResult().isSuccess() ? "SUCCESS" : "FAILED"
            );
            return resolveSecurityInterrupt(ctx, executionId, agentId, tenantId, round,
                    List.of(outcome.toolResult()), state, startMs, conversationId);
        }

        if (pendingToolApprovalService.isDenied(approval)) {
            ToolResult deniedResult = buildDeniedByUserResult(request, approval);
            chatMemory.add(toolHandler.buildResultMessage(
                    request, deniedResult, Map.of("approvalId", approval.getId()), true,
                    null, compressionOptions));
            toolApprovalBatchService.markCallStatus(
                    approval.getBatchId(), approval.getToolCallId(), ToolApprovalBatchService.CALL_STATUS_REJECTED);
            toolApprovalBatchService.markOthersSkippedAfterRestore(approval.getBatchId(), approval.getToolCallId());
            fillLegacyApprovalResults(chatMemory, request, compressionOptions);
            publishApprovalResumeEvent(executionId, round, approval, false, false, startMs);
            agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                    AgentLoopLogTypeEnum.TOOL_RESULT.getCode(), round, request.name(),
                    "用户拒绝待审批工具，approvalId=" + approval.getId(), null, elapsed(startMs));
            return null;
        }

        log.warn("待审批记录状态不可恢复: executionId={}, approvalId={}, status={}",
                executionId, approval.getId(), approval.getDecisionStatus());
        return null;
    }

    private AgentExecutionResult resumeApprovalBatch(AgentExecutionContext ctx, AgentToolSessionFactory.RoundToolContext roundCtx,
                                                      ChatMemory chatMemory, PendingToolApproval approval, ToolApprovalBatch batch,
                                                      AgentEngineParams params, AgentLoopState state, long startMs,
                                                      String conversationId) {
        String executionId = ctx.getExecutionId();
        int round = batch.getRoundNum();
        state.setLastRound(round);
        List<Map<String, Object>> calls = batch.getCallStates();
        boolean currentApproval = calls.stream().anyMatch(call -> approval.getToolCallId().equals(call.get("callId"))
                && ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL.equals(call.get("status")));
        if (!currentApproval || !(pendingToolApprovalService.isApproved(approval) || pendingToolApprovalService.isDenied(approval))) {
            return keepApprovalPaused(ctx, state, round);
        }
        var options = new AgentLoopToolHandler.ToolResultCompressionOptions(
                params.getMaxToolResultMessageLength(), params.getToolRequestSummaryLimit());
        String interruptAction = null;
        List<ToolResult> outcomes = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            ToolExecutionRequest request = toolApprovalBatchService.request(call);
            String status = (String) call.get("status");
            if (ToolApprovalBatchService.CALL_STATUS_RESULT.equals(status)) {
                String recordedAction = (String) call.get("controlAction");
                if (isSecurityInterrupt(recordedAction)) {
                    interruptAction = recordedAction;
                    outcomes.add(ToolResult.builder().callId(request.id()).toolCode(request.name())
                            .controlAction(recordedAction).errorMessage((String) call.get("resultText")).build());
                }
                continue;
            }
            if (interruptAction != null || !state.getBudget().canDispatch()
                    || ToolApprovalBatchService.CALL_STATUS_DISPATCHING.equals(status)) {
                String message = ToolApprovalBatchService.CALL_STATUS_DISPATCHING.equals(status)
                        ? "上次调用已派发，执行结果未知，恢复时跳过重复调用"
                        : "本轮已中断，工具未执行";
                ToolResult skipped = ToolResult.builder().callId(request.id()).toolCode(request.name())
                        .errorMessage(message).success(false).build();
                recordApprovalOutcome(batch, call, request, skipped, options);
                continue;
            }
            if (isInactiveApprovalCall(status)) {
                continue;
            }
            PendingToolApproval decision = approval.getToolCallId().equals(request.id()) ? approval
                    : pendingToolApprovalService.findById((String) call.get("approvalId")).orElse(null);
            if (decision != null && pendingToolApprovalService.isDenied(decision)) {
                recordApprovalOutcome(batch, call, request, buildDeniedByUserResult(request, decision), options);
                publishApprovalResumeEvent(executionId, round, decision, false, false, startMs);
                continue;
            }
            if (!roundCtx.toolServiceContext().toolExecutors().containsKey(request.name())
                    || toolHandler.filterExecutable(List.of(request), roundCtx.toolServiceContext().effectiveTools()).isEmpty()) {
                toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_REJECTED);
                continue;
            }
            if (decision == null || !pendingToolApprovalService.isApproved(decision)) {
                AgentExecutionResult paused = authorizeRestoredCall(ctx, roundCtx, batch, call, request,
                        state, startMs, conversationId);
                if (paused != null) {
                    return paused;
                }
                if (isInactiveApprovalCall((String) call.get("status"))) {
                    continue;
                }
            } else {
                request = pendingToolApprovalService.rebuildToolRequest(decision);
                ToolIoTypeEnum ioType = roundCtx.ioTypeIndex().getOrDefault(request.name(), ToolIoTypeEnum.READ_WRITE);
                ToolExecutionDecisionEnum gateDecision = evaluateToolDecision(ctx, roundCtx, request, ioType, round, conversationId);
                if (gateDecision == ToolExecutionDecisionEnum.DENY || gateDecision == ToolExecutionDecisionEnum.SUGGEST_ONLY
                        || !fireRestoredBeforeHook(ctx, request, round)) {
                    toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_REJECTED);
                    publishApprovalResumeEvent(executionId, round, decision, false, false, startMs);
                    continue;
                }
                if (!pendingToolApprovalService.consume(decision.getId())) {
                    recordApprovalOutcome(batch, call, request, buildApprovalConsumedResult(request, decision), options);
                    continue;
                }
            }
            toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_DISPATCHING);
            if (!state.getBudget().canDispatch()) {
                recordApprovalOutcome(batch, call, request, toolHandler.buildRejectedResult(request), options);
                continue;
            }
            ToolExecutionOutcome outcome = executeWithActivity(ctx, roundCtx, request, options, round);
            ToolResult result = outcome.toolResult();
            toolApprovalBatchService.recordResult(batch, call, outcome.resultMessage().text(),
                    outcome.resultMessage().isError(), result.getControlAction());
            outcomes.add(result);
            if (isSecurityInterrupt(result.getControlAction())) {
                interruptAction = result.getControlAction();
            }
            if (decision != null) {
                publishApprovalResumeEvent(executionId, round, decision, result.isSuccess(), true, startMs);
            }
        }
        writeApprovalBatchResults(chatMemory, calls, options, params);
        if (isStopped(executionId)) {
            return buildStoppedResult(executionId, ctx.getAgentId(), ctx.getTenantId(), round, startMs);
        }
        return resolveSecurityInterrupt(ctx, executionId, ctx.getAgentId(), ctx.getTenantId(), round,
                outcomes, state, startMs, conversationId);
    }

    private boolean isSecurityInterrupt(String action) {
        return SecurityComplianceConstant.DECISION_BLOCK.equals(action) || SecurityComplianceConstant.DECISION_PAUSE.equals(action);
    }

    private boolean isInactiveApprovalCall(String status) {
        return ToolApprovalBatchService.CALL_STATUS_REJECTED.equals(status)
                || ToolApprovalBatchService.CALL_STATUS_LIMITED.equals(status)
                || ToolApprovalBatchService.CALL_STATUS_SUGGESTED.equals(status);
    }

    private AgentExecutionResult authorizeRestoredCall(AgentExecutionContext ctx, AgentToolSessionFactory.RoundToolContext roundCtx,
                                                         ToolApprovalBatch batch, Map<String, Object> call,
                                                         ToolExecutionRequest request, AgentLoopState state,
                                                         long startMs, String conversationId) {
        int round = batch.getRoundNum();
        ToolIoTypeEnum ioType = roundCtx.ioTypeIndex().getOrDefault(request.name(), ToolIoTypeEnum.READ_WRITE);
        if (!fireRestoredBeforeHook(ctx, request, round)) {
            toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_REJECTED);
            return null;
        }
        var decision = evaluateToolDecision(ctx, roundCtx, request, ioType, round, conversationId);
        if (decision == ToolExecutionDecisionEnum.PAUSE_APPROVAL) {
            PendingToolApproval next = createPendingApprovalForTool(ctx, request, ioType, round, conversationId, batch.getId());
            toolApprovalBatchService.linkApproval(batch, request.id(), next.getId());
            AgentExecutionResult paused = keepApprovalPaused(ctx, state, round);
            if (STATUS_PAUSED.equals(paused.getStatus())) {
                executionEventStreamService.publishWithPayload(ctx.getExecutionId(),
                        AgentExecutionEventTypeEnum.APPROVAL_REQUIRED.getCode(), round, "工具 " + request.name() + " 需要审批",
                        buildApprovalRequiredPayload(next, request, ioType, ctx.getExecutionMode()), startMs);
            }
            return paused;
        }
        if (decision == ToolExecutionDecisionEnum.DENY) {
            toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_REJECTED);
        } else if (decision == ToolExecutionDecisionEnum.SUGGEST_ONLY) {
            toolApprovalBatchService.record(batch, call, ToolApprovalBatchService.CALL_STATUS_SUGGESTED);
        }
        return null;
    }

    private ToolExecutionDecisionEnum evaluateToolDecision(AgentExecutionContext ctx,
                                                            AgentToolSessionFactory.RoundToolContext roundCtx,
                                                            ToolExecutionRequest request,
                                                            ToolIoTypeEnum ioType, int round, String conversationId) {
        return toolExecutionGate.evaluate(ToolExecutionGateRequest.builder()
                .tenantId(ctx.getTenantId()).agentId(ctx.getAgentId()).executionId(ctx.getExecutionId())
                .conversationId(conversationId).round(round).policy(resolveExecutionModePolicy(ctx))
                .toolRequest(request).ioType(ioType).systemAgent(ctx.isSystemAgent())
                .desktopTool(roundCtx.isDesktopTool(request.name())).build()).getDecision();
    }

    private boolean fireRestoredBeforeHook(AgentExecutionContext ctx, ToolExecutionRequest request, int round) {
        return hookExecutor.fire(AgentHookType.BEFORE_TOOL_EXECUTE,
                AgentHookContext.builder().executionId(ctx.getExecutionId()).agentId(ctx.getAgentId())
                        .tenantId(ctx.getTenantId()).round(round).toolRequest(request).toolCode(request.name()).build(),
                ctx.getAgentId());
    }

    private AgentExecutionResult keepApprovalPaused(AgentExecutionContext ctx, AgentLoopState state, int round) {
        if (!pauseExecution(ctx.getExecutionId(), null, state, null)) {
            return currentExecutionResult(ctx.getExecutionId(), round);
        }
        return AgentExecutionResult.builder().status(STATUS_PAUSED).rounds(round).conversationId(ctx.getConversationId())
                .tokenInput(state.getTotalTokenInput()).tokenOutput(state.getTotalTokenOutput()).build();
    }

    private void recordApprovalOutcome(ToolApprovalBatch batch, Map<String, Object> call, ToolExecutionRequest request,
                                       ToolResult result, AgentLoopToolHandler.ToolResultCompressionOptions options) {
        ToolExecutionResultMessage message = toolHandler.buildResultMessage(request, result, Map.of(), !result.isSuccess(), null, options);
        toolApprovalBatchService.recordResult(batch, call, message.text(), message.isError(), result.getControlAction());
    }

    private void writeApprovalBatchResults(ChatMemory memory, List<Map<String, Object>> calls,
                                            AgentLoopToolHandler.ToolResultCompressionOptions options, AgentEngineParams params) {
        List<ToolExecutionRequest> requests = calls.stream().map(toolApprovalBatchService::request).toList();
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        int assistantIndex = findToolRound(messages, requests.getFirst().id());
        if (assistantIndex < 0) {
            assistantIndex = messages.size();
            messages.add(AiMessage.from(requests));
        }
        int endIndex = assistantIndex + 1;
        while (endIndex < messages.size() && messages.get(endIndex) instanceof ToolExecutionResultMessage) {
            endIndex++;
        }
        List<ToolExecutionResultMessage> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            var request = requests.get(i);
            if (call.get("resultText") instanceof String text) {
                results.add(ToolExecutionResultMessage.builder().id(request.id()).toolName(request.name())
                        .text(text).isError(Boolean.TRUE.equals(call.get("error"))).build());
                continue;
            }
            String status = (String) call.get("status");
            ToolResult result = ToolApprovalBatchService.CALL_STATUS_LIMITED.equals(status)
                    ? toolHandler.buildLimitResult(toolHandler.toToolCalls(List.of(request)).getFirst(), params.getMaxToolCallsPerRound())
                    : toolHandler.buildRejectedResult(request);
            String suggested = ToolApprovalBatchService.CALL_STATUS_SUGGESTED.equals(status)
                    ? "建议模式下未执行工具 " + request.name() : null;
            results.add(toolHandler.buildResultMessage(request, result, Map.of(), true, suggested, options));
        }
        messages.subList(assistantIndex + 1, endIndex).clear();
        messages.addAll(assistantIndex + 1, results);
        if (memory instanceof ArchivedChatMemory archived) {
            archived.recordResumedToolResults(results);
        }
        memory.set(messages);
    }

    private void fillLegacyApprovalResults(ChatMemory memory, ToolExecutionRequest approval,
                                           AgentLoopToolHandler.ToolResultCompressionOptions options) {
        List<ChatMessage> messages = memory.messages();
        int assistantIndex = findToolRound(messages, approval.id());
        if (assistantIndex < 0) {
            return;
        }
        var present = messages.subList(assistantIndex + 1, messages.size()).stream()
                .takeWhile(ToolExecutionResultMessage.class::isInstance)
                .map(message -> ((ToolExecutionResultMessage) message).id()).collect(Collectors.toSet());
        var assistant = (AiMessage) messages.get(assistantIndex);
        List<ChatMessage> completed = new ArrayList<>(messages);
        List<ToolExecutionResultMessage> skipped = assistant.toolExecutionRequests().stream()
                .filter(request -> !present.contains(request.id()))
                .map(request -> toolHandler.buildResultMessage(request, toolHandler.buildRejectedResult(request),
                        Map.of(), true, "旧版审批批次缺少可恢复的工具参数，已跳过", options)).toList();
        completed.addAll(assistantIndex + 1 + present.size(), skipped);
        if (memory instanceof ArchivedChatMemory archived) {
            archived.recordResumedToolResults(skipped);
        }
        memory.set(completed);
    }

    private int findToolRound(List<ChatMessage> messages, String callId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof AiMessage assistant && assistant.hasToolExecutionRequests()
                    && assistant.toolExecutionRequests().stream().anyMatch(request -> callId.equals(request.id()))) {
                return i;
            }
        }
        return -1;
    }

    PendingToolApproval createPendingApprovalForTool(AgentExecutionContext ctx,
                                                     ToolExecutionRequest request,
                                                     ToolIoTypeEnum ioType,
                                                     int round,
                                                     String conversationId,
                                                     String batchId) {
        PendingToolApproval approval = pendingToolApprovalService.createPending(CreatePendingToolApprovalCommand.builder()
                .tenantId(ctx.getTenantId())
                .agentId(ctx.getAgentId())
                .executionId(ctx.getExecutionId())
                .conversationId(conversationId)
                .roundNum(round)
                .executionMode(StringUtils.hasText(ctx.getExecutionMode()) ? ctx.getExecutionMode() : "auto")
                .ioType(ioType)
                .riskLevel("MEDIUM")
                .toolRequest(request)
                .batchId(batchId)
                .build());
        log.info("已创建待审批工具调用: executionId={}, approvalId={}, toolCode={}, ioType={}",
                ctx.getExecutionId(), approval.getId(), request.name(), ioType != null ? ioType.getCode() : null);
        return approval;
    }

    private ToolResult buildDeniedByUserResult(ToolExecutionRequest request, PendingToolApproval approval) {
        String reason = StringUtils.hasText(approval.getDecisionReason()) ? approval.getDecisionReason() : "用户未提供原因";
        String message = "用户拒绝执行该工具，请调整方案继续。原因: " + reason;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("executed", false);
        payload.put("controlAction", "DENIED_BY_USER");
        payload.put("message", message);
        payload.put("reason", reason);
        payload.put("approvalId", approval.getId());
        return ToolResult.builder()
                .callId(request.id())
                .toolCode(request.name())
                .success(false)
                .result(objectMapper.valueToTree(payload))
                .errorMessage(message)
                .controlAction("DENIED_BY_USER")
                .build();
    }

    private ToolResult buildApprovalConsumedResult(ToolExecutionRequest request, PendingToolApproval approval) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("executed", false);
        payload.put("controlAction", "ALREADY_CONSUMED");
        payload.put("message", "该审批已消费，跳过重复执行（幂等保护）");
        payload.put("approvalId", approval.getId());
        return ToolResult.builder()
                .callId(request.id())
                .toolCode(request.name())
                .success(false)
                .result(objectMapper.valueToTree(payload))
                .errorMessage("该审批已消费，跳过重复执行（幂等保护）")
                .controlAction("ALREADY_CONSUMED")
                .build();
    }

    private void publishApprovalResumeEvent(String executionId,
                                            int round,
                                            PendingToolApproval approval,
                                            boolean success,
                                            boolean executed,
                                            long startMs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.getId());
        payload.put("toolCode", approval.getToolCode());
        payload.put("decision", approval.getDecision());
        payload.put("decisionStatus", approval.getDecisionStatus());
        payload.put("executed", executed);
        payload.put("success", success);
        executionEventStreamService.publishWithPayload(executionId,
                AgentExecutionEventTypeEnum.TOOL_APPROVAL_RESUMED.getCode(), round,
                executed ? "审批后已执行工具: " + approval.getToolCode() : "审批后跳过工具: " + approval.getToolCode(),
                payload, startMs);
    }

    private Map<String, Object> buildApprovalRequiredPayload(PendingToolApproval approval,
                                                             ToolExecutionRequest request,
                                                             ToolIoTypeEnum ioType,
                                                             String executionMode) {
        String arguments = request.arguments() != null ? request.arguments() : "{}";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval != null ? approval.getId() : null);
        payload.put("toolCode", request.name());
        payload.put("args", arguments);
        payload.put("command", arguments);
        payload.put("ioType", ioType != null ? ioType.getCode() : ToolIoTypeEnum.READ_WRITE.getCode());
        payload.put("executionMode", StringUtils.hasText(executionMode) ? executionMode : "auto");
        payload.put("riskLevel", approval != null && StringUtils.hasText(approval.getRiskLevel())
                ? approval.getRiskLevel() : "MEDIUM");
        payload.put("allowedDecisions", List.of("approve", "approve_always", "deny", "edit"));
        return payload;
    }

    private Map<String, Object> buildSuggestPreviewPayload(ToolExecutionRequest request) {
        return buildSuggestPreviewPayload(request, "建议模式下未执行真实工具，仅展示候选操作。");
    }

    private Map<String, Object> buildSuggestPreviewPayload(ToolExecutionRequest request, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("executed", false);
        payload.put("mode", "suggest");
        payload.put("toolCode", request.name());
        payload.put("args", request.arguments() != null ? request.arguments() : "{}");
        payload.put("arguments", request.arguments() != null ? request.arguments() : "{}");
        payload.put("message", message);
        return payload;
    }

    private boolean isApprovalResume(AgentExecutionInputDTO input) {
        return input != null
                && (StringUtils.hasText(input.getApprovalId()) || StringUtils.hasText(input.getApprovalDecision()));
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
        if (ctx.getRecoveryLease() != null) {
            ctx.getRecoveryLease().requireReview();
        }
        Map<String, Object> payload = Map.of("toolCode", interrupted.getToolCode(),
                "controlAction", interrupted.getControlAction());

        if (SecurityComplianceConstant.DECISION_PAUSE.equals(interrupted.getControlAction())) {
            if (!pauseExecution(executionId, message, state, null)) {
                return currentExecutionResult(executionId, round);
            }
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.SECURITY_PAUSED.getCode(), round, null, message, null, elapsed(startMs));
            executionEventStreamService.publishWithPayload(executionId,
                    AgentExecutionEventTypeEnum.REQUIRE_INPUT.getCode(), round, message, payload, startMs);
            recordAgentTrace(executionId, tenantId, agentId, startMs,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_PAUSED);
            logLoopOutcome(executionId, STATUS_PAUSED, AgentLoopEndReason.SECURITY_PAUSED, false);
            return AgentExecutionResult.builder().status(STATUS_PAUSED).endReason(AgentLoopEndReason.SECURITY_PAUSED)
                    .errorMessage(message)
                    .conversationId(conversationId).tokenInput(state.getTotalTokenInput())
                    .tokenOutput(state.getTotalTokenOutput()).rounds(round).build();
        }
        if (SecurityComplianceConstant.DECISION_BLOCK.equals(interrupted.getControlAction())) {
            if (!tryFinalize(executionId, STATUS_FAILED, message, null, state,
                    AgentExecutionEventTypeEnum.BLOCKED, message, startMs)) {
                return currentExecutionResult(executionId, round);
            }
            discardPendingSteeringOnTerminal(executionId);
            agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                    AgentLoopLogTypeEnum.SECURITY_BLOCKED.getCode(), round, null, message, null, elapsed(startMs));
            recordAgentTrace(executionId, tenantId, agentId, startMs,
                    state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_FAILED);
            logLoopOutcome(executionId, STATUS_FAILED, AgentLoopEndReason.SECURITY_BLOCKED, false);
            return AgentExecutionResult.builder().status(STATUS_FAILED).endReason(AgentLoopEndReason.SECURITY_BLOCKED)
                    .errorMessage(message)
                    .conversationId(conversationId).tokenInput(state.getTotalTokenInput())
                    .tokenOutput(state.getTotalTokenOutput()).rounds(round).build();
        }
        return null;
    }

    /**
     * 循环结束后的收尾：正常完成直接构建结果；否则触发强制收敛。
     *
     * <p>硬边界（审计 M1）：deadline 已过或累计 Token 已超硬预算时，
     * 跳过强制收敛/质量修订的模型调用，直接基于已收集证据规则化降级收敛
     * （无证据则失败），保证预算耗尽后零模型调用。
     */
    private AgentExecutionResult finalizeLoop(AgentExecutionContext ctx,
                                               List<LangChain4jResolution> modelChain,
                                               String systemPrompt, ChatMemory chatMemory,
                                               AgentLoopState state, AgentEngineParams params,
                                               String executionId, String agentId, String tenantId,
                                               long startMs, String conversationId) {
        if (isStopped(executionId)) {
            return buildStoppedResult(executionId, agentId, tenantId, state.getLastRound(), startMs);
        }
        AgentExecutionBudget budget = state.getBudget();
        if (budget.remainingMillis() == 0 || (budget.remainingTokens() <= 0 && !state.isLoopCompleted())) {
            String boundary = budget.remainingMillis() == 0 ? "执行截止时间已过" : "累计 Token 预算已用尽";
            return completeOnBudgetBoundary(ctx, chatMemory, state, params, startMs, conversationId, boundary);
        }
        if (state.isLoopCompleted()) {
            AgentExecutionResult result = buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
            if (!STATUS_COMPLETED.equals(result.getStatus())) {
                return result;
            }
            // 触发 ON_LOOP_COMPLETE Hook
            hookExecutor.fire(AgentHookType.ON_LOOP_COMPLETE, AgentHookContext.builder()
                    .executionId(executionId).agentId(agentId).tenantId(tenantId)
                    .finalContent(state.getLastContent())
                    .totalTokenInput(state.getTotalTokenInput()).totalTokenOutput(state.getTotalTokenOutput())
                    .elapsedMs(System.currentTimeMillis() - startMs).build(), agentId);
            return result;
        }

        if (state.getEndReason() == null) {
            state.setEndReason(state.isQualityReflectionPending()
                    ? AgentLoopEndReason.QUALITY_LIMIT : AgentLoopEndReason.ROUND_LIMIT);
        }
        if (!completionHandler.hasCompletionEvidence(chatMemory)) {
            return buildFailedResult(executionId, agentId, tenantId, state.getLastRound(), state, startMs,
                    "执行已到收敛边界，现有证据不足");
        }

        int forceRound = state.getLastRound() + 1;
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.FORCE_COMPLETION.getCode(), state.getLastRound(), null,
                "进入强制收敛输出: " + state.getEndReason(), null, elapsed(startMs));

        try {
            ChatResponse forced = completionHandler.forceCompletion(modelChain.getLast(), systemPrompt, chatMemory,
                    params, executionId, agentId, tenantId, forceRound, startMs, agentLogService,
                    executionEventStreamService, budget);
            AiMessage forcedMsg = forced.aiMessage();
            if (forcedMsg == null || !StringUtils.hasText(forcedMsg.text())) {
                throw new IllegalStateException(AgentLoopPromptConstant.EMPTY_AI_RESPONSE_ERROR);
            }
            state.setLastContent(forcedMsg.text());
            state.setLastRound(forceRound);
            AgentExecutionResult securityInterrupt = validateCandidateOutput(ctx, state, startMs, conversationId);
            if (securityInterrupt != null) {
                return securityInterrupt;
            }
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(forcedMsg);
            if (budget.canDispatch() && state.getQualityReflectionCount() < params.getMaxQualityReflections()
                    && !state.getLastContent().equals(state.getLastReflectedContent())) {
                try {
                    CompletionRevisionResult revision = completionHandler.reviseIfNecessary(ctx, modelChain.getLast(),
                            systemPrompt, chatMemory, state.getLastContent(), params,
                            executionId, agentId, tenantId, forceRound + 1, startMs, agentLogService,
                            executionEventStreamService, budget);
                    state.setLastContent(revision.content());
                    state.setLastRound(Math.max(state.getLastRound(), revision.finalRound()));
                } catch (AgentRunRecovery.LostOwnershipException e) {
                    throw e;
                } catch (Exception e) {
                    log.debug("保留强制收敛结果: executionId={}, reason={}", executionId, e.getMessage());
                }
            }

            return buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
        } catch (AgentRunRecovery.LostOwnershipException ownership) {
            throw ownership;
        } catch (Exception forceEx) {
            if (forceEx instanceof AgentExecutionBudget.BudgetExceededException) {
                return completeOnBudgetBoundary(ctx, chatMemory, state, params, startMs, conversationId,
                        resolveExMsg(forceEx));
            }
            if (completionHandler.hasCompletionEvidence(chatMemory)) {
                String degraded = completionHandler.buildDegraded(ctx, chatMemory, state.getLastRound(), null, forceEx, params);
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.DEGRADED_COMPLETION.getCode(), state.getLastRound(), null,
                        "强制收敛失败，基于已收集证据输出降级结论", null, elapsed(startMs));
                chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
                chatMemory.add(AiMessage.from(degraded));
                state.setLastContent(degraded);
                state.setDegraded(true);
                return buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
            }
            String errMsg = "强制收敛失败: " + resolveExMsg(forceEx);
            return buildFailedResult(executionId, agentId, tenantId, state.getLastRound(), state, startMs, errMsg);
        }
    }

    private AgentExecutionResult completeOnBudgetBoundary(AgentExecutionContext ctx, ChatMemory chatMemory,
                                                           AgentLoopState state, AgentEngineParams params,
                                                           long startMs, String conversationId, String boundary) {
        String executionId = ctx.getExecutionId();
        String agentId = ctx.getAgentId();
        String tenantId = ctx.getTenantId();
        state.setEndReason(state.getBudget().remainingMillis() == 0
                ? AgentLoopEndReason.DEADLINE : AgentLoopEndReason.TOKEN_BUDGET);
        int round = state.getLastRound();
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.FORCE_COMPLETION.getCode(), round, null,
                "执行预算触发，跳过模型收敛: " + boundary, null, elapsed(startMs));
        if (completionHandler.hasCompletionEvidence(chatMemory)) {
            String degraded = completionHandler.buildDegraded(ctx, chatMemory, round, null,
                    new IllegalStateException(boundary), params);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(AiMessage.from(degraded));
            state.setLastContent(degraded);
            state.setDegraded(true);
            return buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
        }
        return buildFailedResult(executionId, agentId, tenantId, round, state, startMs, boundary);
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
        if (!modelInvoker.isRetryable(e) || round < Math.min(3, params.getMaxRounds())
                || !completionHandler.hasCompletionEvidence(chatMemory)) return null;

        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.MODEL_FAILURE_FALLBACK.getCode(), round, null,
                "模型在第 " + round + " 轮持续失败，尝试强制收敛完成当前节点", null, elapsed(startMs));
        try {
            ChatResponse forced = completionHandler.forceCompletion(resolution, systemPrompt, chatMemory,
                    params, executionId, agentId, tenantId, round + 1, startMs, agentLogService,
                    executionEventStreamService, state.getBudget());
            AiMessage forcedMsg = forced.aiMessage();
            boolean hasOutput = forcedMsg != null && StringUtils.hasText(forcedMsg.text());
            state.setDegraded(!hasOutput);
            String content = hasOutput ? forcedMsg.text()
                    : completionHandler.buildDegraded(ctx, chatMemory, round, e, null, params);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(AiMessage.from(content));
            state.setLastContent(content);
            return buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
        } catch (AgentRunRecovery.LostOwnershipException ownership) {
            throw ownership;
        } catch (Exception forceEx) {
            if (forceEx instanceof AgentExecutionBudget.BudgetExceededException) {
                return completeOnBudgetBoundary(ctx, chatMemory, state, params, startMs, conversationId, resolveExMsg(forceEx));
            }
            if (!completionHandler.hasCompletionEvidence(chatMemory)) return null;
            state.setDegraded(true);
            String degraded = completionHandler.buildDegraded(ctx, chatMemory, round, e, forceEx, params);
            chatMemory.add(UserMessage.from(AgentLoopPromptConstant.FORCE_COMPLETION));
            chatMemory.add(AiMessage.from(degraded));
            state.setLastContent(degraded);
            agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                    AgentLoopLogTypeEnum.DEGRADED_COMPLETION.getCode(), round, null,
                    "模型调用与强制收敛均失败，基于已收集证据输出降级结论", null, elapsed(startMs));
            return buildCompletedResult(ctx, executionId, agentId, tenantId, state, startMs, conversationId);
        }
    }

    // =========================================================================
    //  结果构建方法
    // =========================================================================

    private boolean requiresRootDesktopVerification(AgentExecutionContext context) {
        return StringUtils.hasText(context.getDesktopVerificationCommand())
                && !StringUtils.hasText(context.getParentExecutionId());
    }

    private boolean shouldGuideDesktopVerification(AgentExecutionContext context) {
        return StringUtils.hasText(context.getDesktopVerificationCommand())
                && (!StringUtils.hasText(context.getParentExecutionId()) || context.isTeamDesktopVerificationOwner());
    }

    /** 构建正常完成结果，清洗输出并更新执行状态 */
    private AgentExecutionResult buildCompletedResult(AgentExecutionContext ctx, String executionId, String agentId, String tenantId,
                                                       AgentLoopState state, long startMs, String conversationId) {
        if (isStopped(executionId)) {
            return buildStoppedResult(executionId, agentId, tenantId, state.getLastRound(), startMs);
        }
        AgentExecutionResult securityInterrupt = validateCandidateOutput(ctx, state, startMs, conversationId);
        if (securityInterrupt != null) {
            return securityInterrupt;
        }
        if (requiresRootDesktopVerification(ctx) && !agentLogService.hasCurrentDesktopVerification(ctx)) {
            state.setEndReason(AgentLoopEndReason.DESKTOP_VERIFICATION_MISSING);
            return buildFailedResult(executionId, agentId, tenantId, state.getLastRound(), state, startMs,
                    AgentLogService.DESKTOP_VERIFICATION_REQUIRED);
        }
        String sanitized = state.getSecurityCheckedContent();
        state.setLastContent(sanitized);
        if (ctx.getAcceptance() != null) {
            AgentAcceptance.Verdict verdict = ctx.getAcceptance().evaluate(sanitized, state.isDegraded());
            state.setAcceptanceStatus(verdict.status());
            if (!verdict.passed()) {
                state.setEndReason(AgentLoopEndReason.ACCEPTANCE_REJECTED);
                return buildFailedResult(executionId, agentId, tenantId, state.getLastRound(), state, startMs,
                        verdict.explanation());
            }
        }
        if (state.getEndReason() == null) {
            state.setEndReason(AgentLoopEndReason.MODEL_COMPLETED);
        }
        boolean partial = state.getAcceptanceStatus() == AgentAcceptance.Status.PARTIAL;
        boolean desktopFallback = state.isDegraded() && StringUtils.hasText(ctx.getDesktopVerificationCommand())
                && DesktopExecutionTarget.from(ctx.getInputContext()) != null;
        String completedStatus = partial || desktopFallback
                ? AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode() : STATUS_COMPLETED;

        if (!tryFinalize(executionId, completedStatus, null, sanitized, state,
                partial || desktopFallback ? AgentExecutionEventTypeEnum.FALLBACK_USED : AgentExecutionEventTypeEnum.COMPLETED,
                "Agent 执行完成", startMs)) {
            return currentExecutionResult(executionId, state.getLastRound());
        }
        // 终态清理：执行完成后残留的 pending 引导不再消费
        discardPendingSteeringOnTerminal(executionId);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_COMPLETED.getCode(), state.getLastRound(), null,
                "Agent 执行完成", null, elapsed(startMs));

        if (userMemoryExtractionService != null && shouldWriteUserMemory(ctx)) {
            userMemoryExtractionService.saveAfterCompletion(tenantId, ctx.getUserId(), agentId, ctx.getProjectId(), executionId,
                    conversationId, ctx.isTemporaryChat(), ctx.getMemoryWriteEnabled(),
                    () -> compositeChatMemoryStore.loadAuthoredExecutionInputs(conversationId, executionId));
        }

        logLoopOutcome(executionId, completedStatus, state.getEndReason(), state.isDegraded());

        // Agent 经验提取可能调用模型，完成通知后异步执行。
        // Agent 经验按 Agent 共享，项目会话仅写入自己的用户项目记忆。
        if (!ctx.isTemporaryChat() && !StringUtils.hasText(ctx.getProjectId())
                && agentMemoryExtractionService != null && StringUtils.hasText(conversationId)) {
            List<ChatMessage> history = loadMemoryExtractionHistory(conversationId, executionId);
            if (history != null && !history.isEmpty()) {
                agentMemoryExtractionService.extractMemoriesAsync(tenantId, agentId, executionId, history);
            }
        }

        // 异步从记忆中压缩更新 Agent 专属指令
        if (!ctx.isTemporaryChat() && agentInstructionsAutoService != null) {
            agentInstructionsAutoService.updateFromMemoriesAsync(tenantId, agentId, executionId);
        }

        recordAgentTrace(executionId, tenantId, agentId, startMs,
                state.getTotalTokenInput(), state.getTotalTokenOutput(), completedStatus);
        return AgentExecutionResult.builder()
                .status(completedStatus)
                .endReason(state.getEndReason()).degraded(state.isDegraded())
                .outputResult(sanitized)
                .conversationId(conversationId)
                .tokenInput(state.getTotalTokenInput())
                .tokenOutput(state.getTotalTokenOutput())
                .rounds(state.getLastRound())
                .build();
    }

    private AgentExecutionResult validateCandidateOutput(AgentExecutionContext ctx, AgentLoopState state,
                                                        long startMs, String conversationId) {
        String sanitized = qualityChecker.sanitize(state.getLastContent());
        if (Objects.equals(state.getSecurityCheckedContent(), sanitized)) {
            return null;
        }
        state.setSecurityCheckedContent(sanitized);
        String executionId = ctx.getExecutionId();
        String agentId = ctx.getAgentId();
        String tenantId = ctx.getTenantId();
        SecurityCheckDecisionVO securityDecision = evaluateOutputSecurity(agentId, executionId, tenantId, sanitized);
        if (securityDecision != null) {
            String decision = securityDecision.getDecision();
            String message = StringUtils.hasText(securityDecision.getMessage())
                    ? securityDecision.getMessage() : "输出内容命中安全策略";
            if (SecurityComplianceConstant.DECISION_BLOCK.equals(decision)) {
                state.setEndReason(AgentLoopEndReason.SECURITY_BLOCKED);
                if (!tryFinalize(executionId, STATUS_FAILED, message, null, state,
                        AgentExecutionEventTypeEnum.FAILED, "输出安全检查阻断: " + message, startMs)) {
                    return currentExecutionResult(executionId, state.getLastRound());
                }
                // 终态清理：执行失败后残留的 pending 引导不再消费
                discardPendingSteeringOnTerminal(executionId);
                agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                        AgentLoopLogTypeEnum.SECURITY_BLOCKED.getCode(), state.getLastRound(), null,
                        "输出安全检查阻断: " + message, null, elapsed(startMs));
                recordAgentTrace(executionId, tenantId, agentId, startMs,
                        state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_FAILED);
                return AgentExecutionResult.builder()
                        .status(STATUS_FAILED)
                        .endReason(state.getEndReason())
                        .errorMessage("输出安全检查阻断: " + message)
                        .tokenInput(state.getTotalTokenInput())
                        .tokenOutput(state.getTotalTokenOutput())
                        .rounds(state.getLastRound())
                        .build();
            }
            if (SecurityComplianceConstant.DECISION_PAUSE.equals(decision)) {
                state.setEndReason(AgentLoopEndReason.SECURITY_PAUSED);
                if (!pauseExecution(executionId, message, state, sanitized)) {
                    return currentExecutionResult(executionId, state.getLastRound());
                }
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.SECURITY_PAUSED.getCode(), state.getLastRound(), null,
                        "输出安全检查暂停: " + message, null, elapsed(startMs));
                publishEndReason(executionId, AgentExecutionEventTypeEnum.REQUIRE_INPUT, state, "输出安全检查暂停: " + message, startMs);
                recordAgentTrace(executionId, tenantId, agentId, startMs,
                        state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_PAUSED);
                return AgentExecutionResult.builder()
                        .status(STATUS_PAUSED)
                        .endReason(state.getEndReason())
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

        return null;
    }

    private boolean tryFinalize(String executionId, String status, String error, String output,
                                AgentLoopState state, AgentExecutionEventTypeEnum type, String message, long startMs) {
        var event = AgentExecutionEvent.builder().executionId(executionId).eventType(type.getCode())
                .roundNum(state.getLastRound()).message(message).payload(completionPayload(state, output))
                .timestamp(Instant.now()).elapsedMs(elapsed(startMs)).build();
        return agentLogService.tryFinalizeWithEvent(executionId, status, error, state.getTotalTokenInput(),
                state.getTotalTokenOutput(), output, state.getAcceptanceStatus(), event);
    }

    /** 构建失败结果并更新执行状态 */
    private AgentExecutionResult buildFailedResult(String executionId, String agentId, String tenantId,
                                                    int round, AgentLoopState state, long startMs, String errorMessage) {
        if (state.getEndReason() == null) {
            state.setEndReason(AgentLoopEndReason.INTERNAL_ERROR);
        }
        if (!tryFinalize(executionId, STATUS_FAILED, errorMessage, null, state,
                AgentExecutionEventTypeEnum.FAILED, errorMessage, startMs)) {
            return currentExecutionResult(executionId, round);
        }
        // 终态清理：执行失败后残留的 pending 引导不再消费
        discardPendingSteeringOnTerminal(executionId);
        agentLogService.appendLog(executionId, agentId, tenantId, "ERROR",
                AgentLoopLogTypeEnum.EXECUTION_FAILED.getCode(), round, null, errorMessage, null, elapsed(startMs));
        recordAgentTrace(executionId, tenantId, agentId, startMs,
                state.getTotalTokenInput(), state.getTotalTokenOutput(), STATUS_FAILED);
        return AgentExecutionResult.builder()
                .status(STATUS_FAILED)
                .endReason(state.getEndReason())
                .errorMessage(errorMessage)
                .tokenInput(state.getTotalTokenInput())
                .tokenOutput(state.getTotalTokenOutput())
                .rounds(round)
                .build();
    }

    private List<ChatMessage> loadMemoryExtractionHistory(String conversationId, String executionId) {
        try {
            return compositeChatMemoryStore.loadExecutionForExtraction(conversationId, executionId);
        } catch (RuntimeException exception) {
            log.warn("读取长期记忆提取来源失败: executionId={}, errorType={}",
                    executionId, exception.getClass().getSimpleName());
            return List.of();
        }
    }

    boolean shouldWriteUserMemory(AgentExecutionContext ctx) {
        return ctx != null
                && !ctx.isTemporaryChat()
                && !StringUtils.hasText(ctx.getParentExecutionId())
                && StringUtils.hasText(ctx.getUserId())
                && !Boolean.FALSE.equals(ctx.getMemoryWriteEnabled());
    }

    /** 返回已提交的停止结果，由停止请求的胜者发送取消事件。 */
    private AgentExecutionResult buildStoppedResult(String executionId, String agentId, String tenantId,
                                                     int round, long startMs) {
        // 终态清理：执行停止后残留的 pending 引导不再消费
        discardPendingSteeringOnTerminal(executionId);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_STOPPED.getCode(), round, null, "用户已停止执行", null, elapsed(startMs));
        recordAgentTrace(executionId, tenantId, agentId, startMs, 0L, 0L, STATUS_STOPPED);
        logLoopOutcome(executionId, STATUS_STOPPED, AgentLoopEndReason.STOPPED, false);
        return AgentExecutionResult.builder().status(STATUS_STOPPED).endReason(AgentLoopEndReason.STOPPED).rounds(round).build();
    }

    private boolean pauseExecution(String executionId, String message, AgentLoopState state, String output) {
        long elapsedMillis = state.getBudget() == null ? 0L : state.getBudget().elapsedMillis();
        return agentLogService.tryPauseExecution(executionId, message,
                state.getTotalTokenInput(), state.getTotalTokenOutput(), output, elapsedMillis);
    }

    /** 状态更新竞争失败时读取数据库中的实际结果。 */
    private AgentExecutionResult currentExecutionResult(String executionId, int round) {
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            return AgentExecutionResult.builder().status(STATUS_FAILED).errorMessage("执行记录不存在").rounds(round).build();
        }
        return AgentExecutionResult.builder()
                .status(execution.getStatus())
                .endReason(STATUS_STOPPED.equals(execution.getStatus()) ? AgentLoopEndReason.STOPPED : null)
                .outputResult(execution.getOutputResult())
                .conversationId(execution.getConversationId())
                .tokenInput(tokenCount(execution.getTokenInput()))
                .tokenOutput(tokenCount(execution.getTokenOutput()))
                .errorMessage(execution.getErrorMessage())
                .rounds(round)
                .build();
    }

    /**
     * 终态（非 PAUSED）清理 pending 引导：执行已结束不再消费，
     * 清理失败仅记录日志，不得阻断终态流程。
     */
    private void discardPendingSteeringOnTerminal(String executionId) {
        try {
            executionSteeringService.markDiscardedByExecution(executionId);
        } catch (Exception e) {
            log.warn("终态清理 pending 引导失败: executionId={}, error={}", executionId, e.getMessage());
        }
    }

    private Map<String, Object> completionPayload(AgentLoopState state, String content) {
        var payload = new LinkedHashMap<String, Object>();
        if (state.getAcceptanceStatus() != null) {
            payload.put("acceptanceStatus", state.getAcceptanceStatus().code());
        }
        payload.put("endReason", state.getEndReason().name());
        payload.put("degraded", state.isDegraded());
        if (StringUtils.hasText(content)) {
            payload.put("content", content);
            payload.put("finalOutput", content);
            if (state.getAcceptanceStatus() == AgentAcceptance.Status.ACCEPTED
                    || state.getAcceptanceStatus() == AgentAcceptance.Status.PARTIAL) {
                payload.put("outputSha256", AgentAcceptance.outputSha256(content));
            }
        }
        return payload;
    }

    private void publishEndReason(String executionId, AgentExecutionEventTypeEnum eventType,
                                  AgentLoopState state, String message, long startMs) {
        executionEventStreamService.publishWithPayload(executionId, eventType.getCode(), state.getLastRound(),
                message, completionPayload(state, null), startMs);
        String status = eventType == AgentExecutionEventTypeEnum.REQUIRE_INPUT ? STATUS_PAUSED : STATUS_FAILED;
        logLoopOutcome(executionId, status, state.getEndReason(), state.isDegraded());
    }

    private void logLoopOutcome(String executionId, String status, AgentLoopEndReason reason, boolean degraded) {
        log.info("agent_loop_outcome executionId={} status={} endReason={} degraded={}", executionId, status, reason, degraded);
    }

    private void logModelPhase(String executionId, int round, String phase) {
        log.info("agent_model_dispatch executionId={} round={} phase={}", executionId, round, phase);
    }

    private void recordAgentTrace(String traceId,
                                  String tenantId,
                                  String agentId,
                                  long startMs,
                                  long inputTokens,
                                  long outputTokens,
                                  String status) {
        agentTraceService.recordAgentSpan(
                traceId,
                tenantId,
                agentId,
                LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneId.systemDefault()),
                elapsed(startMs),
                inputTokens,
                outputTokens,
                status
        );
    }

    // =========================================================================
    //  消息构建与日志辅助
    // =========================================================================

    /** 构建发送给模型的消息列表（SystemMessage + ChatMemory 历史） */
    private List<ChatMessage> buildMessages(String systemPrompt, ChatMemory chatMemory) {
        return buildMessages(SystemMessage.from(systemPrompt), chatMemory);
    }

    private List<ChatMessage> buildMessages(SystemMessage systemPrompt, ChatMemory chatMemory) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(systemPrompt);
        messages.addAll(chatMemory.messages());
        return messages;
    }

    private record TaskContextInput(UserMessage message, ContextInjector.TaskContextRange range) {}

    private AgentExecutionActivity finishAnalysis(AgentExecutionActivity analysis, AiMessage message) {
        int calls = message != null && message.toolExecutionRequests() != null
                ? message.toolExecutionRequests().size() : 0;
        // 只使用面向用户的工具调用前说明，终稿由原有交付流程处理。
        String commentary = calls > 0 && message.text() != null
                ? activityPreview(INLINE_THINKING.matcher(message.text()).replaceAll(""), 1400) : null;
        String summary = commentary != null ? activityPreview(commentary.replaceAll("\\s+", " "), 96)
                : calls > 0 ? "计划调用 " + calls + " 个工具" : "本轮模型分析完成";
        return analysis.finish(true, summary, commentary, Map.of());
    }

    private ToolExecutionOutcome executeTrackedTool(AgentExecutionContext context,
                                                    AgentToolSessionFactory.RoundToolContext roundContext,
                                                    ToolExecutionRequest request,
                                                    AgentLoopToolHandler.ToolResultCompressionOptions options,
                                                    int round) {
        AgentRunRecovery.Lease lease = context.getRecoveryLease();
        if (lease != null) {
            lease.claimTool(round, request);
        }
        ToolExecutionOutcome outcome = executeWithActivity(context, roundContext, request, options, round);
        if (lease != null) {
            lease.recordTool(round, outcome);
        }
        return outcome;
    }

    private void checkpoint(AgentExecutionContext context, AgentLoopState state, int round) {
        if (context.getRecoveryLease() != null) {
            context.getRecoveryLease().checkpoint(round, state.getTotalTokenInput(), state.getTotalTokenOutput(),
                    state.getBudget().elapsedMillis());
        }
    }

    private AgentExecutionResult restoreToolRound(AgentExecutionContext context, ArchivedChatMemory memory,
                                                  AgentLoopState state, long startMs) {
        var dispatches = context.getRecoveryLease().dispatches();
        var unresolved = dispatches.stream()
                .filter(call -> call.receipt() == null || call.requiresReview())
                .map(call -> call.tool() + " (" + call.id() + ")").sorted().toList();
        if (!unresolved.isEmpty()) {
            state.setEndReason(AgentLoopEndReason.RECOVERY_REVIEW_REQUIRED);
            return buildFailedResult(context.getExecutionId(), context.getAgentId(), context.getTenantId(),
                    state.getLastRound(), state, startMs, "派发结果需人工核对：" + String.join("、", unresolved));
        }
        var recorded = dispatches.stream().collect(Collectors.toMap(AgentRunRecovery.Dispatch::id,
                AgentRunRecovery.Dispatch::receipt));
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        int assistantIndex = messages.size() - 1;
        while (assistantIndex >= 0 && messages.get(assistantIndex) instanceof ToolExecutionResultMessage) {
            assistantIndex--;
        }
        if (assistantIndex < 0 || !(messages.get(assistantIndex) instanceof AiMessage assistant)
                || !assistant.hasToolExecutionRequests()) {
            return null;
        }

        Map<String, ToolExecutionResultMessage> priorResults = new LinkedHashMap<>();
        for (int index = assistantIndex + 1; index < messages.size(); index++) {
            var result = (ToolExecutionResultMessage) messages.get(index);
            priorResults.put(result.id(), result);
        }
        List<ToolExecutionResultMessage> results = assistant.toolExecutionRequests().stream().map(request -> {
            ToolExecutionResultMessage present = priorResults.get(request.id());
            if (present != null) {
                return present;
            }
            return recorded.getOrDefault(request.id(), ToolExecutionResultMessage.builder().id(request.id())
                    .toolName(request.name()).text("执行中断，本次调用没有取得派发记录。").isError(true).build());
        }).toList();
        memory.recordResumedToolResults(results);
        messages.subList(assistantIndex + 1, messages.size()).clear();
        messages.addAll(results);
        memory.set(messages);
        return null;
    }

    private ToolExecutionOutcome executeWithActivity(AgentExecutionContext ctx,
                                                      AgentToolSessionFactory.RoundToolContext roundContext,
                                                      ToolExecutionRequest request,
                                                      AgentLoopToolHandler.ToolResultCompressionOptions options, int round) {
        var executor = roundContext.toolServiceContext() == null ? null
                : roundContext.toolServiceContext().toolExecutors().get(request.name());
        boolean mcp = executor instanceof GuardedToolExecutor guarded
                && SourceTypeEnum.MCP.getCode().equals(guarded.sourceType());
        var kind = AgentExecutionActivity.toolKind(request.name(), mcp);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("toolName", request.name());
        if (StringUtils.hasText(request.id())) {
            fields.put("toolCallId", request.id());
        }
        if (kind != AgentExecutionActivity.Kind.MCP && kind != AgentExecutionActivity.Kind.FUNCTION) {
            fields.putAll(toolActivityFields(request.arguments()));
        }
        var activity = AgentExecutionActivity.start(kind, kind.toolTitle(), fields.getOrDefault("path",
                        fields.getOrDefault("program", request.name())),
                ctx.getTeamMemberRoleName(), round, fields);
        publishActivity(ctx, activity);
        try {
            ToolExecutionOutcome outcome = toolHandler.executeOne(roundContext, request, options);
            ToolResult result = outcome.toolResult();
            Map<String, String> finishedFields = new LinkedHashMap<>(fields);
            finishedFields.put("durationMs", Long.toString(outcome.durationMs()));
            if (kind == AgentExecutionActivity.Kind.COMMAND) {
                addCommandExitCode(result.getResult(), finishedFields);
            }
            publishActivity(ctx, activity.finish(result.isSuccess(), activity.summary(), null, finishedFields));
            return outcome;
        } catch (RuntimeException e) {
            publishActivity(ctx, activity.finish(false, activity.summary(), null, fields));
            throw e;
        }
    }

    private void addCommandExitCode(JsonNode output, Map<String, String> fields) {
        if (output == null) {
            return;
        }
        if (output.isTextual()) {
            try {
                output = objectMapper.readTree(output.asText());
            } catch (JsonProcessingException e) {
                return;
            }
        }
        if (output != null && output.path("exitCode").isIntegralNumber()) {
            fields.put("exitCode", output.path("exitCode").asText());
        }
    }

    private Map<String, String> toolActivityFields(String rawArguments) {
        if (!StringUtils.hasText(rawArguments)) {
            return Map.of();
        }
        try {
            JsonNode args = objectMapper.readTree(rawArguments);
            if (args == null || !args.isObject()) {
                return Map.of();
            }
            Map<String, String> fields = new LinkedHashMap<>();
            // 仅显示可定位操作的字段，避免把文件正文、凭据或工具输出复制到活动记录。
            for (String key : List.of("path", "source", "target", "destination", "cwd", "workdir", "workingDirectory")) {
                String value = activityPreview(args.path(key).asText(null), 240);
                if (value != null) {
                    fields.put(key, value);
                }
            }
            String command = args.path("command").asText("").strip().split("\\s+", 2)[0];
            if (command.matches("[a-zA-Z0-9_./-]{1,80}")) {
                fields.put("program", command);
            }
            return fields;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String activityPreview(String value, int limit) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.strip();
        int end = text.offsetByCodePoints(0, Math.min(limit, text.codePointCount(0, text.length())));
        return end == text.length() ? text : text.substring(0, end) + "…";
    }

    private void publishActivity(AgentExecutionContext ctx, AgentExecutionActivity activity) {
        // 团队成员的活动跟随父执行推送，成员角色保留在步骤中。
        String agentId = ctx.getTeamAgentId() == null ? ctx.getAgentId() : ctx.getTeamAgentId();
        executionEventStreamService.publishActivity(activityExecutionId(ctx), agentId, ctx.getTenantId(), activity);
    }

    private String activityExecutionId(AgentExecutionContext ctx) {
        return ctx.getParentExecutionId() == null ? ctx.getExecutionId() : ctx.getParentExecutionId();
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
        Integer reportedOutput = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null;
        int outputTokens = reportedOutput != null ? reportedOutput : 0;
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
        executionEventStreamService.publishSimple(executionId, AgentExecutionEventTypeEnum.CONTEXT_INJECT.getCode(),
                0, "Agent 执行启动", startMs);
    }

    /** 恢复执行时将人工输入追加到 ChatMemory */
    private void addResumeMessage(String executionId, String agentId, String tenantId,
                                   ChatMemory chatMemory, AgentExecutionInputDTO input, long startMs) {
        String content = StringUtils.hasText(input.getMessage()) ? input.getMessage() : "继续执行";
        addAuthoredInput(chatMemory, UserMessage.from(content), input.getMessage());
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.EXECUTION_START.getCode(), 0, null,
                "恢复执行，追加人工输入: " + content, null, elapsed(startMs));
    }

    // =========================================================================
    //  上下文与模型链解析
    // =========================================================================

    /** 解析或生成 conversationId */
    String resolveConversationId(AgentExecutionContext ctx) {
        String conversationId = StringUtils.hasText(ctx.getConversationId())
                ? ctx.getConversationId()
                : UUID.randomUUID().toString();
        // 会话 Key 租户隔离：Solo 会话加租户前缀（幂等）；Team 子会话（team: 开头）保持原样，待 Team 恢复语义任务统一
        if (StringUtils.hasText(ctx.getTenantId())
                && !conversationId.startsWith(ctx.getTenantId() + ":")
                && !conversationId.startsWith("team:")) {
            return ctx.getTenantId() + ":" + conversationId;
        }
        return conversationId;
    }

    /** 解析当前执行所属用户，异步执行时不能依赖 ThreadLocal。 */
    private String resolveUserId(AgentExecutionContext ctx, String executionId) {
        if (StringUtils.hasText(ctx.getUserId())) {
            return ctx.getUserId();
        }
        if (!StringUtils.hasText(executionId)) {
            return null;
        }
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        return execution != null ? execution.getCreatedBy() : null;
    }

    /** 将 conversationId 持久化到执行记录 */
    private void persistConversationId(String executionId, String conversationId) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setConversationId(conversationId);
        agentExecutionMapper.updateById(update);
    }

    /**
     * 解析模型降级链。
     *
     * <p>绑定类型为 model_group 时接入模型组解析（负载均衡选链首 + 组内降级链尾，
     * shardKey=tenantId:executionId 保证同执行重试幂等）；否则按单模型路由规则降级。
     */
    private List<LangChain4jResolution> resolveModelChain(AgentExecutionContext ctx) {
        String reasoning = ctx.getReasoningStrength() != null ? ctx.getReasoningStrength() : "medium";
        List<LangChain4jResolution> chain;
        if ("model_group".equals(ctx.getAgentModelType()) && StringUtils.hasText(ctx.getAgentModelGroupId())) {
            String shardKey = (ctx.getTenantId() != null ? ctx.getTenantId() : "") + ":" + ctx.getExecutionId();
            chain = aiModelRouter.resolveGroupChainForExecution(ctx.getAgentModelGroupId(), shardKey, reasoning);
        } else {
            chain = aiModelRouter.resolveRouteChainForExecution(ctx.getModel(), reasoning);
        }
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
        if (ctx.getAcceptance() != null) {
            additionalContexts.add(ctx.getAcceptance().prompt());
        } else if (StringUtils.hasText(ctx.getOutputFormat())) {
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
        if (ctx.isSystemAgent()) {
            additionalContexts.add(SystemAgentPromptConstant.IDENTITY);
        }
        ctx.setAdditionalSystemContexts(additionalContexts);
    }

    /**
     * 合并执行模式指令到 additionalSystemContexts（系统Agent专属）
     */
    private List<String> mergeExecutionModeInstruction(AgentExecutionContext ctx) {
        List<String> base = ctx.getAdditionalSystemContexts();
        List<String> result = base != null ? new ArrayList<>(base) : new ArrayList<>();
        if (!ctx.isSystemAgent()) {
            return result;
        }
        ExecutionModePolicy policy = resolveExecutionModePolicy(ctx);
        result.addFirst(executionModePromptContributor.buildPrompt(policy));
        return result;
    }

    private ExecutionModePolicy resolveExecutionModePolicy(AgentExecutionContext ctx) {
        ExecutionModePolicy policy = ctx.getExecutionModePolicy();
        if (policy == null) {
            policy = executionModePolicyResolver.resolve(ctx, null);
            ctx.setExecutionModePolicy(policy);
        }
        return policy;
    }

    /** 构建额外上下文字符串（inputPrompt + inputContext，不含附件） */
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
        return sb.toString().trim();
    }

    private void persistContextBudgetSnapshot(AgentExecutionContext ctx,
                                              ContextInjector.PromptBuildResult promptBuildResult,
                                              AiModelConfig modelConfig) {
        try {
            AgentExecutionContext budgetContext = AgentExecutionContext.builder()
                    .executionId(ctx.getExecutionId())
                    .agentId(ctx.getAgentId())
                    .tenantId(ctx.getTenantId())
                    .inputPrompt(ctx.getInputPrompt())
                    .inputContext(ctx.getInputContext())
                    .teamAgentId(ctx.getTeamAgentId())
                    .teamMemberId(ctx.getTeamMemberId())
                    .teamMemberRoleName(ctx.getTeamMemberRoleName())
                    .teamMemberRoleType(ctx.getTeamMemberRoleType())
                    .additionalSystemContexts(List.of(promptBuildResult.prompt()))
                    .model(modelConfig.getModelId())
                    .sandboxPolicy(ctx.getSandboxPolicy())
                    .build();
            Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
            snapshot.put("contextBudget", agentContextBudgetService.buildSnapshot(budgetContext, ctx.getSandboxPolicy()));
            AgentExecution update = new AgentExecution();
            update.setId(ctx.getExecutionId());
            update.setSandboxPolicySnapshot(snapshot);
            agentExecutionMapper.updateById(update);
        } catch (Exception exception) {
            log.warn("写入上下文预算快照失败: executionId={}, error={}", ctx.getExecutionId(), exception.getMessage());
        }
    }

    /** 构建用户消息，多模态模型将图片附件转为 ImageContent */
    private UserMessage buildUserMessage(AgentExecutionContext ctx) {
        String text = buildExtraContext(ctx);
        String attachmentScope = StringUtils.hasText(ctx.getAttachmentSourceConversationId())
                ? ctx.getAttachmentSourceConversationId() : ctx.getConversationId();
        return multimodalMessageBuilder.buildForExecution(text, ctx.getAttachmentIds(), true,
                ctx.getTenantId(), ctx.getUserId(), attachmentScope);
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
    /**
     * 消费执行中用户引导：pending → applied CAS，按 seq 升序注入为追加用户消息
     */
    boolean consumePendingSteeringIfAny(String executionId, String agentId, String tenantId,
                                        String conversationId, ArchivedChatMemory chatMemory, int round, long startMs) {
        List<ExecutionSteering> pending = executionSteeringService.findPending(executionId);
        boolean applied = false;
        for (ExecutionSteering steering : pending) {
            var message = executionSteeringService.applyAndArchive(steering, conversationId);
            if (message.isEmpty()) {
                continue; // 已被消费（幂等保护）
            }
            chatMemory.addArchivedUserInput(message.get());
            applied = true;
            agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                    AgentLoopLogTypeEnum.EXECUTION_START.getCode(), round, null,
                    "注入执行中用户引导: seq=" + steering.getSeq(), null, elapsed(startMs));
        }
        return applied;
    }

    private void addAuthoredInput(ChatMemory chatMemory, UserMessage message, String authoredText) {
        if (chatMemory instanceof ArchivedChatMemory archive) {
            archive.addUserInput(message, authoredText);
        } else {
            chatMemory.add(message);
        }
    }

    int resolveMaxExecutionMinutes(AgentExecutionContext ctx) {
        SandboxPolicy policy = ctx.getSandboxPolicy();
        if (policy != null && policy.getMaxExecutionMinutes() > 0) {
            return policy.getMaxExecutionMinutes();
        }
        return defaultMaxExecutionMinutes;
    }

    long resolveMaxTokenBudget(AgentExecutionContext ctx) {
        SandboxPolicy policy = ctx.getSandboxPolicy();
        if (policy != null && policy.getMaxTokenBudget() > 0) {
            return policy.getMaxTokenBudget();
        }
        return defaultMaxTokenBudget;
    }

    private long tokenCount(Long count) {
        return count == null ? 0L : Math.max(0L, count);
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
        if (!state.getBudget().canDispatch() || state.getPendingShadowReview() != null
                || !StringUtils.hasText(state.getLastContent())) {
            return;
        }
        var shadowReview = shadowReviewService.submitStructuralReview(
                ctx, state.getLastContent(), state.getQualityReflectionCount(), params
        );
        if (shadowReview == null) {
            return;
        }
        state.setPendingShadowReview(shadowReview);
        agentLogService.appendLog(executionId, agentId, tenantId, "INFO",
                AgentLoopLogTypeEnum.QUALITY_SHADOW_SUBMITTED.getCode(), round, null,
                "已提交结构化影子质量审核", null, elapsed(startMs));
        executionEventStreamService.publishSimple(executionId,
                AgentExecutionEventTypeEnum.QUALITY_SHADOW_SUBMITTED.getCode(),
                round, "已提交结构化影子质量审核", startMs);
    }

    private QualityReflectionFeedback consumePendingShadowReview(ChatMemory chatMemory,
                                                                  AgentLoopState state,
                                                                  AgentEngineParams params,
                                                                  String executionId, String agentId, String tenantId,
                                                                  int round, long startMs) {
        var review = state.getPendingShadowReview();
        state.setPendingShadowReview(null);
        if (review == null) {
            return null;
        }
        QualityReflectionFeedback feedback = shadowReviewService.await(review, shadowWaitMillis(state, params));
        if (!review.isDone()) {
            review.cancel(false);
        }
        if (feedback != null && state.getBudget().canDispatch()) {
            applyQualityFeedback(chatMemory, state, feedback, executionId, agentId, tenantId, round, startMs, true);
            return feedback;
        }
        return null;
    }

    private long shadowWaitMillis(AgentLoopState state, AgentEngineParams params) {
        return Math.min(params.getShadowQualityReviewAwaitMillis(), state.getBudget().remainingMillis());
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
        state.setLastReflectedContent(state.getLastContent());
        state.setQualityReflectionCount(state.getQualityReflectionCount() + 1);
        state.setQualityReflectionPending(true);
        state.setLoopCompleted(false);
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
