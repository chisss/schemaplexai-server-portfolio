package com.schemaplexai.service.agent.runtime.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentModelBindingTypeEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.TeamMemberExecutionMode;
import com.schemaplexai.common.enums.TeamMemberRoleTypeEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.entity.AgentTeamMemberContextBinding;
import com.schemaplexai.model.entity.AgentTeamMemberToolBinding;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.agent.execution.AgentEngineParams;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.agent.execution.AgentEngineConfigLoader;
import com.schemaplexai.service.agent.execution.AgentRunRecovery;
import com.schemaplexai.service.agent.execution.AgentTelemetry;
import com.schemaplexai.service.agent.execution.ExecutionStopSignal;
import com.schemaplexai.service.agent.execution.AgentAcceptance;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionContext.TeamTaskContextProjection;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.TeamExecutionBudget;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.execution.AgentExecutionActivity;
import com.schemaplexai.service.agent.runtime.AgentRuntimeStrategy;
import com.schemaplexai.service.agent.runtime.team.TeamConversationArchive.DesktopEvidence;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.security.AgentOutputSecurityGuard;
import com.schemaplexai.service.security.AgentOutputSecurityGuard.Decision;
import com.schemaplexai.service.security.AgentOutputSecurityGuard.OutputGuardContext;
import com.schemaplexai.service.security.AgentOutputSecurityGuard.OutputGuardResult;
import com.schemaplexai.service.workflow.ArtifactSceneResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.action.AsyncNodeActionWithConfig.node_async;

/**
 * Team Agent 运行时策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamAgentRuntimeStrategy implements AgentRuntimeStrategy {

    private static final String TEAM_WARNING_TITLE = "## Team 执行告警";
    private static final String TEAM_METADATA_TITLE = "## Team 聚合元数据";
    private static final String TEAM_EXECUTION_FAILED = "Team Agent 执行失败，请查看执行详情或联系管理员。";
    private static final String TEAM_MODEL_CHANNEL_UNAVAILABLE = "Team 成员的模型通道均暂不可用，请稍后重试或联系管理员。";
    private static final String DESKTOP_UPSTREAM_EVIDENCE_HEADER = "## 上游本机实际回执\n";
    private static final int MAX_LEAD_PROMPT_CHARS = 4200;
    private static final int MAX_LEAD_EVIDENCE_CHARS = 2200;
    private static final int MAX_DESKTOP_VERIFIER_EVIDENCE_CHARS = 1900;
    private static final int MAX_MEMBER_PROMPT_CHARS = 2600;
    private static final int MAX_LEAD_OUTPUT_CHARS = 2600;
    private static final int MAX_MEMBER_OUTPUT_CHARS = 1200;
    /** 所有成员写入 State 的 outputResult 上限：完整内容经证据服务或 Lead 子执行记录读取，State 仅保留摘要。 */
    private static final int MAX_STATE_MEMBER_OUTPUT_CHARS = 8000;
    /** 成员执行超时看门狗宽限秒数：父级 orTimeout 在子执行 deadline 之后再多等待该秒数，避免与子执行正常收敛竞争。 */
    private static final long MEMBER_TIMEOUT_GRACE_SECONDS = 30L;
    private static final int DEFAULT_CHILD_MEMBER_MAX_ROUNDS = 8;
    private static final int DEFAULT_CHILD_LEAD_MAX_ROUNDS = 4;
    private static final int DESKTOP_VERIFICATION_MAX_ROUNDS = 12;
    private static final int DEFAULT_CHILD_MEMBER_MAX_TOOL_CALLS = 4;
    private static final int DEFAULT_CHILD_LEAD_MAX_TOOL_CALLS = 2;
    private static final int DESKTOP_VERIFICATION_MAX_TOOL_CALLS = 8;
    private static final int MIN_CHILD_MEMBER_MAX_ROUNDS = 4;
    private static final int MIN_CHILD_LEAD_MAX_ROUNDS = 2;
    private static final int MIN_CHILD_MAX_MESSAGES = 16;
    private static final int MAX_CHILD_MAX_MESSAGES = 80;
    private static final Pattern MIN_COMPANY_COUNT_PATTERN = Pattern.compile(
            "(?:至少|不少于|不低于|at\\s+least)(?:[^\\d\\n]{0,12})?(\\d+)\\s*(?:家|companies?)",
            Pattern.CASE_INSENSITIVE
    );
    private static final String LOG_LEVEL_INFO = "INFO";
    private static final String LOG_LEVEL_WARN = "WARN";
    private static final String LOG_LEVEL_ERROR = "ERROR";
    static final String DESKTOP_VERIFICATION_OWNER_ID = "desktopVerificationOwnerId";
    static final String DELIVERY_REPAIR_ROUND = "deliveryRepairRound";
    static final String DELIVERY_REPAIR_TARGET = "deliveryRepairTarget";
    static final String DELIVERY_REPAIR_TEXT_ONLY = "deliveryRepairTextOnly";

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentTeamMemberToolBindingMapper agentTeamMemberToolBindingMapper;
    private final BuiltinToolMapper builtinToolMapper;
    private final AgentTeamMemberContextBindingMapper agentTeamMemberContextBindingMapper;
    private final ContextItemMapper contextItemMapper;
    private final AgentExecutionEngine agentExecutionEngine;
    private final AgentLogService agentLogService;
    private final AgentLoopQualityChecker qualityChecker;
    private final ExecutionEventStreamService executionEventStreamService;
    private final AgentContextPublisher agentContextPublisher;
    private final TeamCheckpointStore checkpointStore;
    private final DesktopAttachmentService desktopAttachments;
    private final SandboxPolicyResolver sandboxPolicyResolver;
    private final ExecutionSteeringService executionSteeringService;
    private final TeamQualityDetector teamQualityDetector;
    private final TeamConversationArchive conversationArchive;
    private final TeamChildApprovalService teamChildApprovalService;
    private final TeamExecutionBudget teamExecutionBudget;
    private ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider;
    private AgentOutputSecurityGuard outputSecurityGuard;

    @Autowired(required = false)
    void setSecurityRuntimeGuardServiceProvider(
            ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider) {
        this.securityRuntimeGuardServiceProvider = securityRuntimeGuardServiceProvider;
        this.outputSecurityGuard = null;
    }

    private AgentEngineConfigLoader teamEngineConfigLoader;

    @Autowired
    void setTeamEngineConfigLoader(AgentEngineConfigLoader teamEngineConfigLoader) {
        this.teamEngineConfigLoader = teamEngineConfigLoader;
    }

    private AgentTraceService agentTraceService;

    @Autowired
    void setAgentTraceService(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }
    private final Executor teamOrchestrationPool;
    private final LangGraphTeamRuntimeAdapter langGraphRuntimeAdapter = new LangGraphTeamRuntimeAdapter();
    private static final String RUNTIME_INPUTS = "schemaplexai.team.runtimeInputs";

    private AgentRunRecovery runRecovery;

    @Autowired
    void setRunRecovery(AgentRunRecovery runRecovery) {
        this.runRecovery = runRecovery;
    }

    private ExecutionStopSignal executionStopSignal;

    @Autowired
    void setExecutionStopSignal(ExecutionStopSignal executionStopSignal) {
        this.executionStopSignal = executionStopSignal;
    }

    private final Cache<Set<String>, Set<String>> readOnlyToolCache = Caffeine.newBuilder()
            .maximumSize(500).expireAfterWrite(Duration.ofMinutes(1)).build();

    private CompiledTeamGraph cachedGraph;

    private record CompiledTeamGraph(CompileConfig config, CompiledGraph<TeamGraphState> graph) { }

    private record RuntimeInputs(Agent agent, AgentExecution execution, AgentExecutionContext context,
                                 List<AgentTeamMember> members, MemberApprovalResume approval,
                                 boolean projectable) { }

    @Override
    public AgentTypeEnum supportType() {
        return AgentTypeEnum.TEAM;
    }

    @Override
    @Async("teamOrchestrationPool")
    public CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context) {
        if (!claimExecution(execution, AgentExecutionStatusEnum.QUEUED)) {
            return CompletableFuture.completedFuture(currentExecutionResult(execution));
        }
        try (var lease = claimTeamLease(context)) {
            if (runRecovery != null && lease == null) {
                return CompletableFuture.completedFuture(currentExecutionResult(execution));
            }
            if (lease != null) lease.bind();
            return CompletableFuture.completedFuture(run(agent, execution, context, null, true));
        } catch (Exception exception) {
            boolean allowFallback = context == null || (context.getAcceptance() == null
                    && !StringUtils.hasText(context.getDesktopVerificationCommand()));
            return CompletableFuture.completedFuture(finishUnexpectedFailure(execution, exception, "execute", allowFallback, context));
        }
    }

    @Override
    @Async("evalTeamOrchestrationPool")
    public CompletableFuture<AgentExecutionResult> executeForEvaluation(Agent agent, AgentExecution execution,
                                                                         AgentExecutionContext context) {
        return execute(agent, execution, context);
    }

    @Override
    public CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution,
                                                          AgentExecutionContext context, AgentExecutionInputDTO input) {
        if (!claimExecution(execution, AgentExecutionStatusEnum.PAUSED)) {
            return CompletableFuture.completedFuture(currentExecutionResult(execution));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try (var lease = claimTeamLease(context)) {
                    if (runRecovery != null && lease == null) return currentExecutionResult(execution);
                    if (lease != null) lease.bind();
                    // 准入暂停发生在初始化图之前，复核后以原任务创建首个 checkpoint。
                    boolean beforeFirstRound = input != null && input.isResumeBeforeFirstRound();
                    return run(agent, execution, context, beforeFirstRound ? null : input, false);
                } catch (Exception exception) {
                    return finishUnexpectedFailure(execution, exception, "resume", false, context);
                }
            }, teamOrchestrationPool);
        } catch (RejectedExecutionException exception) {
            agentLogService.tryTransitionExecutionStatus(execution.getId(), AgentExecutionStatusEnum.RUNNING.getCode(),
                    AgentExecutionStatusEnum.PAUSED.getCode(), execution.getErrorMessage(), null, null, null);
            throw exception;
        }
    }

    private AgentRunRecovery.Lease claimTeamLease(AgentExecutionContext context) {
        return runRecovery == null || context == null ? null : runRecovery.claimTeam(context);
    }

    private boolean claimExecution(AgentExecution execution, AgentExecutionStatusEnum expectedStatus) {
        boolean claimed = agentLogService.tryTransitionExecutionStatus(execution.getId(), expectedStatus.getCode(),
                AgentExecutionStatusEnum.RUNNING.getCode(), null, null, null, null);
        if (claimed) execution.setStartedAt(LocalDateTime.now());
        return claimed;
    }

    private AgentExecutionResult run(Agent agent, AgentExecution execution, AgentExecutionContext context,
                                     AgentExecutionInputDTO resumeInput, boolean freshExecution) throws Exception {
        var span = AgentTelemetry.execution("team", context);
        try (var scope = span.makeCurrent()) {
            var result = runGraph(agent, execution, context, resumeInput, freshExecution);
            AgentTelemetry.outcome(span, result);
            return result;
        } catch (Exception failure) {
            AgentTelemetry.failure(span, failure);
            throw failure;
        } finally {
            span.end();
        }
    }

    private AgentExecutionResult runGraph(Agent agent, AgentExecution execution, AgentExecutionContext context,
                                          AgentExecutionInputDTO resumeInput, boolean freshExecution) throws Exception {
        if (context.isTemporaryChat()) {
            execution.setTemporaryChat(true);
        }
        if (Boolean.FALSE.equals(context.getMemoryWriteEnabled())) {
            execution.setMemoryWriteEnabled(false);
        }
        context.setTeamBudget(teamExecutionBudget.open(execution.getTenantId(), execution.getId(),
                context.getSandboxPolicy()));
        List<AgentTeamMember> members = loadMembers(agent.getId());
        if (members.isEmpty()) {
            throw new TeamInputException("Team Agent 未配置成员");
        }
        validateRuntimeConfiguration(members);

        String threadId = StringUtils.hasText(execution.getGraphThreadId())
                ? execution.getGraphThreadId()
                : "team-" + execution.getId();
        String checkpointNamespace = "team-agent-" + agent.getId();
        persistGraphMetadata(execution.getId(), threadId, checkpointNamespace);
        execution.setGraphThreadId(threadId);
        execution.setCheckpointNamespace(checkpointNamespace);
        if (resumeInput == null) {
            publishParentEvent(execution, AgentExecutionEventTypeEnum.ROUND_START, "Team Agent 开始编排成员执行", null);
            var planning = AgentExecutionActivity.start(AgentExecutionActivity.Kind.TEAM, "团队分工",
                    "已安排 " + members.size() + " 名成员", null, 0, Map.of());
            executionEventStreamService.publishActivity(execution.getId(), execution.getAgentId(),
                    execution.getTenantId(), planning.finish(true, planning.summary(),
                            buildPlanSummary(agent, members), Map.of()));
        }

        if (teamEngineConfigLoader != null) {
            context.setTeamEngineParams(teamEngineConfigLoader.load(agent.getId(), context));
        }
        Map<String, Object> initialState = resumeInput == null ? buildInitialState(execution, context, members) : Map.of();
        MemberApprovalResume memberApproval = resolveMemberApproval(execution, resumeInput);
        boolean projectableDefinition = freshExecution && !StringUtils.hasText(execution.getTaskId())
                && !StringUtils.hasText(execution.getSpecId()) && !StringUtils.hasText(execution.getParentExecutionId());
        var compiled = compiledGraph();
        CompileConfig compileConfig = compiled.config();
        var compiledGraph = compiled.graph();
        var runnableConfig = langGraphRuntimeAdapter.buildRunnableConfig(threadId).updateMetadata(Map.of(
                RUNTIME_INPUTS, new RuntimeInputs(agent, execution, context, List.copyOf(members),
                        memberApproval, projectableDefinition)));
        boolean allowFallback = freshExecution && context.getAcceptance() == null
                && !StringUtils.hasText(context.getDesktopVerificationCommand());

        try {
            var finalState = resumeInput == null
                    ? compiledGraph.invoke(initialState, runnableConfig)
                    : resumeGraph(compiledGraph, runnableConfig, execution, resumeInput);
            TeamGraphState state = resolveGraphState(finalState, compiledGraph, runnableConfig, initialState);
            AgentExecutionResult result = finalizeExecution(execution, context, state);
            if (!AgentExecutionStatusEnum.PAUSED.getCode().equals(result.getStatus())) {
                releaseCheckpoint(compileConfig, runnableConfig, execution.getId());
            }
            return result;
        } catch (Exception failure) {
            AgentExecutionResult result = finishUnexpectedFailure(execution, failure,
                    resumeInput == null ? "execute" : "resume", allowFallback, context);
            if (!AgentExecutionStatusEnum.PAUSED.getCode().equals(result.getStatus())) {
                releaseCheckpoint(compileConfig, runnableConfig, execution.getId());
            }
            return result;
        }
    }

    private void releaseCheckpoint(CompileConfig compileConfig, RunnableConfig runnableConfig, String executionId) {
        try {
            langGraphRuntimeAdapter.releaseCheckpoint(compileConfig, runnableConfig);
        } catch (Exception exception) {
            log.warn("释放 Team checkpoint 失败: executionId={}", executionId, exception);
        }
    }

    private synchronized CompiledTeamGraph compiledGraph() throws Exception {
        if (cachedGraph == null) {
            var graph = buildGraph();
            CompileConfig config = langGraphRuntimeAdapter.buildCompileConfig(checkpointStore.buildSaver(graph));
            cachedGraph = new CompiledTeamGraph(config, graph.compile(config));
        }
        return cachedGraph;
    }

    private RuntimeInputs inputs(RunnableConfig config) {
        return (RuntimeInputs) config.metadata(RUNTIME_INPUTS).orElseThrow();
    }

    private StateGraph<TeamGraphState> buildGraph() throws Exception {
        var memberCycleSubgraph = buildMemberCycleSubgraph().compile();
        return new StateGraph<>(TeamGraphState::new)
                .addNode(TeamGraphConstants.NODE_PLAN, node_async((state, config) -> {
                    var runtime = inputs(config);
                    return Map.of(TeamGraphConstants.STATE_PLAN_SUMMARY, buildPlanSummary(runtime.agent(), runtime.members()));
                }))
                .addNode(TeamGraphConstants.NODE_MEMBER_CYCLE, memberCycleSubgraph)
                .addNode(TeamGraphConstants.NODE_AWAIT_INPUT, node_async((state, config) -> {
                    // 幂等契约（审计 LA-02）：此节点在 resume 时会被 interruptBefore 重新执行，用于重新校验附件。
                    // 仅允许只读校验与状态回写，禁止在此引入任何写操作/副作用。
                    validateAttachments(inputs(config).execution(), state.attachmentIds());
                    return Map.of(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.RUNNING.getCode());
                }))
                .addEdge(START, TeamGraphConstants.NODE_PLAN)
                .addEdge(TeamGraphConstants.NODE_PLAN, TeamGraphConstants.NODE_MEMBER_CYCLE)
                .addConditionalEdges(TeamGraphConstants.NODE_MEMBER_CYCLE,
                        edge_async(state -> {
                            String finalStatus = state.finalStatus();
                            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(finalStatus)) {
                                return TeamGraphConstants.NODE_AWAIT_INPUT;
                            }
                            return END;
                        }),
                        Map.of(
                                TeamGraphConstants.NODE_AWAIT_INPUT, TeamGraphConstants.NODE_AWAIT_INPUT,
                                END, END
                        ))
                .addEdge(TeamGraphConstants.NODE_AWAIT_INPUT, TeamGraphConstants.NODE_MEMBER_CYCLE);
    }

    private StateGraph<TeamGraphState> buildMemberCycleSubgraph() throws Exception {
        return new StateGraph<>(TeamGraphState::new)
                .addNode(TeamGraphConstants.NODE_EXECUTE_MEMBERS, node_async((state, config) -> {
                    var runtime = inputs(config);
                    return Map.of(TeamGraphConstants.STATE_MEMBER_RESULTS, executeMembers(
                            runtime.agent(), runtime.execution(), runtime.context(), runtime.members(),
                            state, runtime.approval(), runtime.projectable()));
                }))
                .addNode(TeamGraphConstants.NODE_ADVISOR, node_async((state, config) -> {
                    var runtime = inputs(config);
                    return applyAdvisorDecisions(runtime.agent(), runtime.execution(), runtime.context(),
                            runtime.members(), state);
                }))
                .addNode(TeamGraphConstants.NODE_LEAD, node_async((state, config) -> {
                    var runtime = inputs(config);
                    return Map.of(TeamGraphConstants.STATE_MEMBER_RESULTS, executeLeadMember(
                            runtime.agent(), runtime.execution(), runtime.context(), runtime.members(),
                            state, runtime.projectable()));
                }))
                .addNode(TeamGraphConstants.NODE_AGGREGATE, node_async(state ->
                        aggregate(state.memberResults(), state.retryCount())
                ))
                .addNode(TeamGraphConstants.NODE_QUALITY_GATE, node_async((state, config) -> {
                    var runtime = inputs(config);
                    return evaluateAggregateQuality(runtime.execution(), runtime.context(), state);
                }))
                .addNode(TeamGraphConstants.NODE_RETRY, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_RETRY_COUNT, state.retryCount() + 1)
                ))
                .addEdge(START, TeamGraphConstants.NODE_EXECUTE_MEMBERS)
                .addEdge(TeamGraphConstants.NODE_EXECUTE_MEMBERS, TeamGraphConstants.NODE_ADVISOR)
                .addEdge(TeamGraphConstants.NODE_ADVISOR, TeamGraphConstants.NODE_LEAD)
                .addEdge(TeamGraphConstants.NODE_LEAD, TeamGraphConstants.NODE_AGGREGATE)
                .addEdge(TeamGraphConstants.NODE_AGGREGATE, TeamGraphConstants.NODE_QUALITY_GATE)
                .addConditionalEdges(TeamGraphConstants.NODE_QUALITY_GATE,
                        edge_async(state -> TeamGraphConstants.QUALITY_GATE_RETRY.equals(state.qualityGateDecision())
                                && state.retryCount() < state.maxMemberRetries()
                                ? TeamGraphConstants.NODE_RETRY
                                : END),
                        Map.of(
                                TeamGraphConstants.NODE_RETRY, TeamGraphConstants.NODE_RETRY,
                                END, END
                        ))
                .addEdge(TeamGraphConstants.NODE_RETRY, TeamGraphConstants.NODE_EXECUTE_MEMBERS);
    }

    private List<Map<String, Object>> executeMembers(Agent agent, AgentExecution execution, AgentExecutionContext parentContext,
                                                     List<AgentTeamMember> members, TeamGraphState state,
                                                     MemberApprovalResume approval, boolean projectableDefinition) {
        // 父执行已停止（如工作流编排超时写入 stopped）：跳过本轮成员执行，防后续轮次继续产生模型/工具副作用
        if (isParentExecutionStopped(execution.getId())) {
            log.info("父执行已被停止，跳过本轮成员执行: executionId={}", execution.getId());
            return List.of(Map.of(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.STOPPED.getCode()));
        }
        parentContext.setTeamMemberSystemContexts(state.memberSystemContexts());
        parentContext.setTeamMemberToolBindings(loadMemberToolBindings(execution.getTenantId(), agent.getId(),
                members.stream().map(AgentTeamMember::getId).toList()));
        parentContext.setTeamEngineParams(AgentEngineParams.builder()
                .memberExecutionTimeoutMinutes(state.memberTimeoutMinutes()).build());
        String userInput = mergePendingSteering(execution.getId(), state.userInput());
        parentContext.setTeamEffectiveUserInput(userInput);
        int retryCount = state.retryCount();
        Map<String, Object> userOptions = state.userOptions();
        String retryFeedback = state.qualityGateSummary();
        boolean projectableCycle = projectableDefinition && retryCount == 0
                && !StringUtils.hasText(userInput) && CollectionUtils.isEmpty(userOptions)
                && !StringUtils.hasText(retryFeedback) && CollectionUtils.isEmpty(state.attachmentIds());
        List<Map<String, Object>> previousResults = state.memberResults();
        updateMemberEvidenceReferences(parentContext, previousResults);
        String verificationOwnerId = state.value(DESKTOP_VERIFICATION_OWNER_ID, "");
        AgentTeamMember verificationOwner = members.stream()
                .filter(member -> verificationOwnerId.equals(member.getId()))
                .findFirst().orElse(null);
        if (retryCount > 0 && retryCount == state.value(DELIVERY_REPAIR_ROUND, 0)) {
            return repairDelivery(agent, execution, parentContext, members, state, approval, verificationOwner,
                    previousResults, userInput);
        }
        // 成功成员复用（审计 P0-3）：恢复/质量重试时不重复执行已完成成员，防写工具重复副作用
        Set<String> completedMemberIds = resolveCompletedMemberIds(previousResults);
        List<Map<String, Object>> memberResults = new ArrayList<>();
        if (!completedMemberIds.isEmpty()) {
            memberResults.addAll(previousResults.stream()
                    .filter(result -> completedMemberIds.contains(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))))
                    .toList());
        }

        Set<String> advisorSkippedMemberIds = new LinkedHashSet<>(state.advisorSkippedMemberIds());
        memberResults.removeIf(result -> advisorSkippedMemberIds.contains(
                asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))));
        AgentTeamMember leadMember = null;
        List<AgentTeamMember> advisorMembers = new ArrayList<>();
        List<AgentTeamMember> contributorMembers = new ArrayList<>();
        for (AgentTeamMember member : members) {
            if (advisorSkippedMemberIds.contains(member.getId())) {
                log.info("按 Advisor 决策跳过成员: memberId={}, executionId={}", member.getId(), execution.getId());
                continue;
            }
            if (completedMemberIds.contains(member.getId())) {
                log.info("复用已完成成员结果，跳过执行: memberId={}, executionId={}", member.getId(), execution.getId());
                continue;
            }
            if (leadMember == null && isLeadRole(member.getRoleType())) {
                leadMember = member;
                continue;
            }
            if (isAdvisorRole(member.getRoleType())) {
                advisorMembers.add(member);
                continue;
            }
            contributorMembers.add(member);
        }

        if (leadMember == null && advisorMembers.isEmpty() && contributorMembers.isEmpty()) {
            return memberResults;
        }

        // 依赖声明（审计 LA-07）：按 dependsOn 拓扑排序贡献者，替代对数据库 sort_order 的单一依赖
        contributorMembers = orderContributorsByDependencies(contributorMembers);

        // 先恢复审批所属的子执行，避免前序角色重试再次暂停整条流水线。
        AgentExecution approvedChild = approval == null ? null : agentExecutionMapper.selectById(approval.child().getId());
        if (approvedChild != null && AgentExecutionStatusEnum.PAUSED.getCode().equals(approvedChild.getStatus())) {
            AgentTeamMember approvedMember = members.stream()
                    .filter(member -> member.getId().equals(approvedChild.getTeamMemberId()))
                    .filter(member -> !completedMemberIds.contains(member.getId()))
                    .findFirst().orElse(null);
            if (approvedMember != null) {
                boolean resumingLead = approvedMember.equals(leadMember);
                String upstreamEvidence = resumingLead
                        ? buildLeadEvidencePrompt(previousResults, parentContext, verificationOwner)
                        : buildMemberEvidencePrompt(previousResults);
                var approvedResult = executeMember(agent, execution, parentContext, approvedMember, retryCount,
                        userInput, userOptions, retryFeedback, upstreamEvidence,
                        resumingLead ? state.attachmentIds() : List.of(),
                        new MemberApprovalResume(approvedChild, approval.input()), verificationOwner, false).join();
                memberResults.add(approvedResult);
                if (hasPausedMemberResult(memberResults)) {
                    return memberResults;
                }
                contributorMembers.remove(approvedMember);
                advisorMembers.remove(approvedMember);
                if (resumingLead) {
                    leadMember = null;
                }
            }
        }

        // 依赖/对等读取声明（审计 LA-07/LA-08）：存在 dependsOn 或 can_read_peers 时强制串行，保证前置成员先完成
        TeamMemberExecutionMode mode = DesktopExecutionTarget.from(parentContext.getInputContext()) != null
                || hasDependencies(contributorMembers)
                || hasReadablePeers(contributorMembers)
                ? TeamMemberExecutionMode.PIPELINE
                : resolveExecutionMode(contributorMembers, parentContext.getTeamMemberToolBindings());
        log.info("Team 成员执行模式: mode={}, contributors={}, executionId={}",
                mode.getCode(), contributorMembers.size(), execution.getId());

        if (mode == TeamMemberExecutionMode.PARALLEL && contributorMembers.size() > 1) {
            memberResults.addAll(executeMemberBatch(
                    agent, execution, parentContext, contributorMembers, retryCount, userInput, userOptions, retryFeedback,
                    buildMemberEvidencePrompt(memberResults), verificationOwner, projectableCycle
            ));
        } else {
            executeContributorsPipeline(agent, execution, parentContext, contributorMembers, retryCount,
                    userInput, userOptions, retryFeedback, memberResults, verificationOwner, projectableCycle);
        }

        if (hasPausedMemberResult(memberResults)) {
            return memberResults;
        }
        for (AgentTeamMember advisor : advisorMembers) {
            updateMemberEvidenceReferences(parentContext, memberResults);
            String advisorEvidence = buildLeadDeliveryEvidence(memberResults, parentContext, advisor,
                    verificationOwner, false);
            memberResults.add(executeMember(agent, execution, parentContext, advisor, retryCount,
                    userInput, userOptions, retryFeedback, advisorEvidence, List.of(), null,
                    verificationOwner, false).join());
            if (hasPausedMemberResult(memberResults)) {
                break;
            }
        }
        return memberResults;
    }

    private static final Pattern ADVISOR_DIRECTIVE_PATTERN = Pattern.compile(
            "(?im)^\\s*TEAM_ADVISOR\\s+(APPEND|SKIP)\\s+(\\S+)(?:\\s+(.*))?\\s*$");

    record AdvisorDecision(String action, String memberId, String reason) {
    }

    List<AdvisorDecision> parseAdvisorDecisions(String output) {
        if (!StringUtils.hasText(output)) {
            return List.of();
        }
        List<AdvisorDecision> decisions = new ArrayList<>();
        Matcher matcher = ADVISOR_DIRECTIVE_PATTERN.matcher(output);
        while (matcher.find()) {
            decisions.add(new AdvisorDecision(matcher.group(1), matcher.group(2),
                    safeText(matcher.group(3), "Advisor 编排决策")));
        }
        return decisions;
    }

    private Map<String, Object> applyAdvisorDecisions(Agent agent, AgentExecution execution,
                                                       AgentExecutionContext context, List<AgentTeamMember> members,
                                                       TeamGraphState state) {
        List<Map<String, Object>> results = new ArrayList<>(state.memberResults());
        Set<String> processed = new LinkedHashSet<>(state.advisorProcessedExecutionIds());
        Set<String> skippedMemberIds = new LinkedHashSet<>(state.advisorSkippedMemberIds());
        List<Map<String, Object>> pendingAdvisors = results.stream()
                .filter(result -> isAdvisorRole(asText(result.get(TeamGraphConstants.RESULT_ROLE_TYPE))))
                .filter(result -> teamQualityDetector.isSuccessfulOutputStatus(
                        asText(result.get(TeamGraphConstants.RESULT_STATUS))))
                .filter(result -> {
                    String executionId = asText(result.get(TeamGraphConstants.RESULT_EXECUTION_ID));
                    return StringUtils.hasText(executionId) && !processed.contains(executionId);
                })
                .toList();
        for (Map<String, Object> advisorResult : pendingAdvisors) {
            String advisorExecutionId = asText(advisorResult.get(TeamGraphConstants.RESULT_EXECUTION_ID));
            processed.add(advisorExecutionId);
            List<AdvisorDecision> decisions = parseAdvisorDecisions(
                    asText(advisorResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT)));
            for (AdvisorDecision decision : decisions.stream().filter(d -> "SKIP".equals(d.action())).toList()) {
                AgentTeamMember target = findAdvisorTarget(members, decision.memberId());
                if (target == null) {
                    log.warn("Advisor SKIP 引用了非贡献者成员，已忽略: memberId={}", decision.memberId());
                    continue;
                }
                skippedMemberIds.add(target.getId());
                results.removeIf(result -> target.getId().equals(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))));
            }
            Set<String> appended = new LinkedHashSet<>();
            for (AdvisorDecision decision : decisions.stream().filter(d -> "APPEND".equals(d.action())).toList()) {
                AgentTeamMember target = findAdvisorTarget(members, decision.memberId());
                if (target == null || !appended.add(target.getId())) {
                    log.warn("Advisor APPEND 引用了非贡献者成员或重复成员，已忽略: memberId={}", decision.memberId());
                    continue;
                }
                Map<String, Object> previous = results.stream()
                        .filter(result -> target.getId().equals(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))))
                        .findFirst().orElse(null);
                if (previous != null && teamQualityDetector.isSuccessfulOutputStatus(
                        asText(previous.get(TeamGraphConstants.RESULT_STATUS)))) {
                    log.warn("Advisor APPEND 不允许重复执行已成功成员，已忽略: memberId={}", target.getId());
                    continue;
                }
                results.removeIf(result -> target.getId().equals(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))));
                updateMemberEvidenceReferences(context, results);
                String feedback = "Advisor 要求追加执行: " + decision.reason();
                Map<String, Object> appendedResult = executeMember(agent, execution, context, target,
                        state.retryCount(), context.getTeamEffectiveUserInput(), state.userOptions(), feedback,
                        buildMemberEvidencePrompt(results), List.of(), null, null, false).join();
                results.add(appendedResult);
                if (hasPausedMemberResult(results)) {
                    break;
                }
            }
        }
        return Map.of(
                TeamGraphConstants.STATE_MEMBER_RESULTS, results,
                TeamGraphConstants.STATE_ADVISOR_PROCESSED_EXECUTION_IDS, List.copyOf(processed),
                TeamGraphConstants.STATE_ADVISOR_SKIPPED_MEMBER_IDS, List.copyOf(skippedMemberIds)
        );
    }

    private AgentTeamMember findAdvisorTarget(List<AgentTeamMember> members, String memberId) {
        return members.stream()
                .filter(member -> member.getId().equals(memberId))
                .filter(member -> !isLeadRole(member.getRoleType()) && !isAdvisorRole(member.getRoleType()))
                .findFirst().orElse(null);
    }

    private List<Map<String, Object>> executeLeadMember(Agent agent, AgentExecution execution,
                                                         AgentExecutionContext context, List<AgentTeamMember> members,
                                                         TeamGraphState state, boolean projectableDefinition) {
        List<Map<String, Object>> results = new ArrayList<>(state.memberResults());
        AgentTeamMember lead = members.stream().filter(member -> isLeadRole(member.getRoleType()))
                .findFirst().orElse(null);
        boolean stopped = results.stream().anyMatch(result -> AgentExecutionStatusEnum.STOPPED.getCode()
                .equals(asText(result.get(TeamGraphConstants.RESULT_STATUS))));
        if (lead == null || stopped || hasPausedMemberResult(results)) {
            return results;
        }
        boolean alreadyCompleted = results.stream()
                .filter(result -> lead.getId().equals(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))))
                .anyMatch(result -> teamQualityDetector.isSuccessfulOutputStatus(
                        asText(result.get(TeamGraphConstants.RESULT_STATUS))));
        if (alreadyCompleted) {
            return results;
        }
        AgentTeamMember verificationOwner = members.stream()
                .filter(member -> member.getId().equals(state.value(DESKTOP_VERIFICATION_OWNER_ID, "")))
                .findFirst().orElse(null);
        updateMemberEvidenceReferences(context, results);
        String userInput = context.getTeamEffectiveUserInput() != null
                ? context.getTeamEffectiveUserInput() : state.userInput();
        boolean projectableCycle = projectableDefinition && state.retryCount() == 0
                && !StringUtils.hasText(userInput) && CollectionUtils.isEmpty(state.userOptions())
                && !StringUtils.hasText(state.qualityGateSummary()) && CollectionUtils.isEmpty(state.attachmentIds());
        String leadEvidence = buildLeadDeliveryEvidence(results, context, lead, verificationOwner,
                projectableCycle && retainsOriginalTask(context.getInputPrompt(), lead));
        results.add(executeMember(agent, execution, context, lead, state.retryCount(), userInput,
                state.userOptions(), state.qualityGateSummary(), leadEvidence, state.attachmentIds(), null,
                verificationOwner, projectableCycle).join());
        return results;
    }

    private List<Map<String, Object>> repairDelivery(Agent agent, AgentExecution execution,
                                                      AgentExecutionContext context, List<AgentTeamMember> members,
                                                      TeamGraphState state, MemberApprovalResume approval,
                                                      AgentTeamMember verificationOwner,
                                                      List<Map<String, Object>> previous, String userInput) {
        String target = state.value(DELIVERY_REPAIR_TARGET, "");
        boolean textOnly = state.value(DELIVERY_REPAIR_TEXT_ONLY, false);
        AgentTeamMember member = members.stream()
                .filter(candidate -> textOnly ? isLeadRole(candidate.getRoleType()) : target.equals(candidate.getId()))
                .findFirst().orElse(null);
        if (member == null) return previous;

        List<Map<String, Object>> results = new ArrayList<>(previous.stream()
                .filter(result -> !member.getId().equals(result.get(TeamGraphConstants.RESULT_MEMBER_ID))).toList());
        updateMemberEvidenceReferences(context, results);
        String evidence = isLeadRole(member.getRoleType())
                ? buildLeadDeliveryEvidence(results, context, member, verificationOwner, false)
                : buildMemberEvidencePrompt(results);
        String feedback = state.qualityGateSummary();
        if (textOnly && StringUtils.hasText(state.finalOutput())) {
            feedback += "\n上一版终稿：" + compressStructuredText(state.finalOutput(), MAX_LEAD_OUTPUT_CHARS);
        }
        results.add(executeMember(agent, execution, context, member, state.retryCount(), userInput,
                state.userOptions(), feedback, evidence, isLeadRole(member.getRoleType()) ? state.attachmentIds() : List.of(),
                approval, verificationOwner, false, textOnly).join());
        return results;
    }

    TeamMemberExecutionMode resolveExecutionMode(List<AgentTeamMember> contributors,
                                                  Map<String, List<AgentToolBinding>> memberBindings) {
        if (contributors.size() <= 1) return TeamMemberExecutionMode.PIPELINE;
        Set<String> toolCodes = new LinkedHashSet<>();
        for (AgentTeamMember member : contributors) {
            var bindings = memberBindings.getOrDefault(member.getId(), List.of());
            if (bindings.isEmpty() || bindings.stream().anyMatch(binding ->
                    !SourceTypeEnum.BUILTIN.getCode().equalsIgnoreCase(binding.getSourceType())
                            || !StringUtils.hasText(binding.getToolCode()))) {
                // 继承绑定或第三方工具的只读性无法证明。
                return TeamMemberExecutionMode.PIPELINE;
            }
            bindings.forEach(binding -> toolCodes.add(binding.getToolCode()));
        }
        Set<String> readOnlyCodes = readOnlyToolCache.get(Set.copyOf(toolCodes), codes ->
                builtinToolMapper.selectList(new LambdaQueryWrapper<BuiltinTool>()
                                .in(BuiltinTool::getCode, codes).eq(BuiltinTool::getEnabled, true)).stream()
                        .filter(tool -> ToolIoTypeEnum.fromCode(tool.getIoType()) == ToolIoTypeEnum.READ)
                        .map(BuiltinTool::getCode).collect(Collectors.toUnmodifiableSet()));
        return readOnlyCodes.containsAll(toolCodes) ? TeamMemberExecutionMode.PARALLEL : TeamMemberExecutionMode.PIPELINE;
    }

    /**
     * 从上一轮成员结果中解析已完成成员 ID 集合（恢复/重试复用判据）
     */
    Set<String> resolveCompletedMemberIds(List<Map<String, Object>> previousResults) {
        if (previousResults == null) {
            return Set.of();
        }
        return previousResults.stream()
                .filter(result -> teamQualityDetector.isSuccessfulOutputStatus(asText(result.get(TeamGraphConstants.RESULT_STATUS))))
                .map(result -> asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID)))
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    /**
     * 解析成员依赖 ID（审计 LA-07）：逗号分隔的 depends_on 配置，去空白与空项，保持声明顺序。
     */
    Set<String> parseDependencyIds(String dependsOn) {
        if (!StringUtils.hasText(dependsOn)) {
            return Set.of();
        }
        return Arrays.stream(dependsOn.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 贡献者是否声明了依赖（审计 LA-07）：任一成员含 dependsOn 即需按依赖顺序串行执行。
     */
    boolean hasDependencies(List<AgentTeamMember> contributors) {
        return contributors.stream().anyMatch(member -> StringUtils.hasText(member.getDependsOn()));
    }

    /**
     * 贡献者是否声明了对等读取（审计 LA-08）：任一成员含 can_read_peers 即需串行执行，保证可读队友先完成。
     */
    boolean hasReadablePeers(List<AgentTeamMember> contributors) {
        return contributors.stream().anyMatch(member -> StringUtils.hasText(member.getCanReadPeers()));
    }

    /**
     * 按 dependsOn 对贡献者做稳定拓扑排序（审计 LA-07），替代对数据库 sort_order 的单一依赖。
     * <p>未知依赖（引用已完成成员/Lead/拼写错误）告警但不阻断；检测到依赖环视为配置错误，快速失败而非退回原顺序。</p>
     */
    List<AgentTeamMember> orderContributorsByDependencies(List<AgentTeamMember> contributors) {
        if (contributors.size() <= 1) {
            return new ArrayList<>(contributors);
        }
        Map<String, AgentTeamMember> byId = contributors.stream()
                .collect(Collectors.toMap(AgentTeamMember::getId, member -> member, (a, b) -> a, LinkedHashMap::new));
        Map<String, Set<String>> remaining = new LinkedHashMap<>();
        for (AgentTeamMember member : contributors) {
            Set<String> declared = parseDependencyIds(member.getDependsOn());
            declared.stream().filter(id -> !byId.containsKey(id)).forEach(id ->
                    log.warn("Team 成员 {} 声明的依赖 {} 不在待执行贡献者集合中（可能已完成或配置错误），已忽略",
                            member.getId(), id));
            Set<String> deps = declared.stream()
                    .filter(byId::containsKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            remaining.put(member.getId(), deps);
        }
        List<AgentTeamMember> sorted = new ArrayList<>(contributors.size());
        while (sorted.size() < contributors.size()) {
            AgentTeamMember next = null;
            for (AgentTeamMember member : contributors) {
                if (!remaining.containsKey(member.getId())) {
                    continue;
                }
                if (remaining.get(member.getId()).isEmpty()) {
                    next = member;
                    break;
                }
            }
            if (next == null) {
                throw new TeamInputException("Team 成员依赖声明存在环，无法确定执行顺序: members="
                        + contributors.stream().map(AgentTeamMember::getId).toList());
            }
            final AgentTeamMember emit = next;
            sorted.add(emit);
            remaining.remove(emit.getId());
            remaining.values().forEach(deps -> deps.remove(emit.getId()));
        }
        return sorted;
    }

    /**
     * 决定子执行可读的同队证据执行 ID 集合（审计 LA-08）：
     * Leader 与 Advisor 读取全队已完成成员；贡献者仅能读取其 can_read_peers 显式声明的成员执行 ID（按 memberId→executionId 映射过滤），
     * 其余返回 null，杜绝把全队证据误授给任意声明了 can_read_peers 的成员。
     */
    List<String> resolveTeamMemberEvidenceIds(AgentTeamMember member, AgentExecutionContext parentContext) {
        if (isLeadRole(member.getRoleType()) || isAdvisorRole(member.getRoleType())) {
            List<String> evidenceIds = parentContext.getTeamMemberEvidenceIds();
            return evidenceIds == null || evidenceIds.isEmpty() ? null : List.copyOf(evidenceIds);
        }
        Set<String> peerMemberIds = parseDependencyIds(member.getCanReadPeers());
        if (peerMemberIds.isEmpty()) {
            return null;
        }
        Map<String, String> executionByMember = parentContext.getTeamMemberExecutionByMemberId();
        if (executionByMember == null || executionByMember.isEmpty()) {
            return null;
        }
        List<String> readable = peerMemberIds.stream()
                .map(executionByMember::get)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return readable.isEmpty() ? null : readable;
    }

    /**
     * 成员轮开始前领取待应用的引导，与本轮输入一同传给成员。
     */
    private String mergePendingSteering(String executionId, String input) {
        List<ExecutionSteering> pending = executionSteeringService.findPending(executionId);
        if (pending.isEmpty()) {
            return input;
        }
        StringBuilder guidance = new StringBuilder();
        for (ExecutionSteering steering : pending) {
            if (executionSteeringService.markApplied(steering.getId())) {
                guidance.append(steering.getContent()).append("\n");
            }
        }
        if (guidance.isEmpty()) {
            return input;
        }
        return (StringUtils.hasText(input) ? input + "\n\n" : "")
                + ExecutionSteeringService.buildSteeringWrapper(guidance.toString());
    }

    /**
     * 查找可恢复的子执行（PAUSED 且属于当前父执行+成员），恢复时复用其 ID 继续
     */
    private AgentExecution findResumableChildExecution(AgentExecution parent, String teamMemberId, String conversationId) {
        return agentExecutionMapper.selectOne(new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getParentExecutionId, parent.getId())
                .eq(AgentExecution::getTenantId, parent.getTenantId())
                .eq(AgentExecution::getAgentId, parent.getAgentId())
                .eq(AgentExecution::getTeamMemberId, teamMemberId)
                .eq(AgentExecution::getConversationId, conversationId)
                .eq(AgentExecution::getStatus, AgentExecutionStatusEnum.PAUSED.getCode())
                .orderByDesc(AgentExecution::getCreatedAt, AgentExecution::getId)
                .last("LIMIT 1"));
    }

    /**
     * 构建成员子执行上下文。
     *
     * <p>安全继承（审计 P0-2）：sandboxPolicy 优先继承父执行策略，父策略缺失时按成员维度解析兜底；
     * executionMode 直接继承父执行（父 PLAN/SUGGEST 时子执行保持同语义，不再回退 auto）。</p>
     */
    AgentExecutionContext buildChildExecutionContext(Agent agent, AgentExecution parentExecution,
                                                     AgentExecutionContext parentContext, AgentTeamMember member,
                                                     int retryCount, String userInput, Map<String, Object> userOptions,
                                                     String retryFeedback, String upstreamEvidence,
                                                     AgentExecution childExecution, AgentTeamMember verificationOwner) {
        return buildChildExecutionContext(agent, parentExecution, parentContext, member, retryCount, userInput,
                userOptions, retryFeedback, upstreamEvidence, childExecution, verificationOwner, false);
    }

    private AgentExecutionContext buildChildExecutionContext(Agent agent, AgentExecution parentExecution,
                                                             AgentExecutionContext parentContext, AgentTeamMember member,
                                                             int retryCount, String userInput, Map<String, Object> userOptions,
                                                             String retryFeedback, String upstreamEvidence,
                                                             AgentExecution childExecution, AgentTeamMember verificationOwner,
                                                             boolean projectableCycle) {
        String modelName = StringUtils.hasText(member.getModelOverride()) ? member.getModelOverride() : parentExecution.getAiModel();
        boolean temporaryChat = parentContext.isTemporaryChat()
                || Boolean.TRUE.equals(childExecution.getTemporaryChat());
        Boolean childWriteEnabled = childExecution.getMemoryWriteEnabled();
        Boolean memoryWriteEnabled = Boolean.FALSE.equals(parentContext.getMemoryWriteEnabled())
                || Boolean.FALSE.equals(childWriteEnabled)
                ? Boolean.FALSE : parentContext.getMemoryWriteEnabled();
        var batchBindings = parentContext.getTeamMemberToolBindings();
        List<AgentToolBinding> toolBindings = batchBindings == null
                ? loadMemberToolBindings(parentExecution.getTenantId(), agent.getId(), List.of(member.getId())).get(member.getId())
                : batchBindings.get(member.getId());
        boolean verifiedDesktop = isVerifiedDesktop(parentContext);
        boolean ownsVerification = verifiedDesktop && verificationOwner != null
                && member.getId().equals(verificationOwner.getId());
        String originalPrompt = parentContext.getInputPrompt();
        String memberPrompt;
        // 回执标题仅由本次执行的设备归档交接生成。恢复、重试和非完整工单沿用原提示。
        if (ownsVerification && projectableCycle && retainsOriginalTask(originalPrompt, member)
                && upstreamEvidence != null && upstreamEvidence.startsWith(DESKTOP_UPSTREAM_EVIDENCE_HEADER)) {
            memberPrompt = buildDesktopVerifierPrompt(originalPrompt, member, upstreamEvidence);
        } else {
            String desktopHandoff = verifiedDesktop
                    ? buildDesktopHandoff(member, toolBindings, verificationOwner, ownsVerification) : null;
            memberPrompt = buildMemberPrompt(originalPrompt, member, retryCount, userInput,
                    userOptions, retryFeedback, upstreamEvidence, desktopHandoff);
        }
        String childPrompt = withPreviousTurns(memberPrompt, parentExecution, temporaryChat);
        int maxRounds = resolveChildMaxRounds(parentContext, member, ownsVerification);
        int maxToolCalls = resolveChildMaxToolCalls(parentContext, member, ownsVerification);
        AgentExecutionContext childContext = AgentExecutionContext.builder()
                .executionId(childExecution.getId())
                .agentId(agent.getId())
                .tenantId(parentExecution.getTenantId())
                .userId(parentContext.getUserId())
                .projectId(parentContext.getProjectId())
                .parentExecutionId(parentExecution.getId())
                .traceParent(AgentTelemetry.capture())
                .executionDeadlineEpochMillis(System.currentTimeMillis()
                        + TimeUnit.MINUTES.toMillis(parentContext.getTeamEngineParams() == null
                                ? 10 : parentContext.getTeamEngineParams().getMemberExecutionTimeoutMinutes()))
                .evaluation(parentContext.isEvaluation())
                .teamBudget(parentContext.getTeamBudget() != null && (ownsVerification || isLeadRole(member.getRoleType()))
                        ? parentContext.getTeamBudget().forFinalization() : parentContext.getTeamBudget())
                .teamAgentId(agent.getId())
                .teamMemberId(member.getId())
                .teamMemberRoleName(member.getRoleName())
                .teamMemberRoleType(member.getRoleType())
                .teamMemberEvidenceIds(resolveTeamMemberEvidenceIds(member, parentContext))
                .inputPrompt(childPrompt)
                .inputContext(parentContext.getInputContext())
                .model(modelName)
                .agentModelType(AgentModelBindingTypeEnum.MODEL.getCode())
                .conversationId(childExecution.getConversationId())
                .maxRounds(maxRounds)
                .maxToolCallsPerRound(maxToolCalls)
                .maxMessages(resolveChildMaxMessages(parentContext, maxRounds, maxToolCalls))
                .overrideToolBindings(toolBindings)
                .additionalSystemContexts(memberSystemContexts(parentContext, member))
                .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                .sandboxPolicy(resolveChildSandboxPolicy(agent, parentContext, member))
                .desktopVerificationCommand(parentContext.getDesktopVerificationCommand())
                .desktopVerificationDelegated(verifiedDesktop && verificationOwner != null && !ownsVerification)
                .teamDesktopVerificationOwner(ownsVerification)
                .executionMode(StringUtils.hasText(parentContext.getExecutionMode())
                        ? parentContext.getExecutionMode()
                        : "auto")
                .stream(parentContext.isStream())
                .temporaryChat(temporaryChat)
                .memoryWriteEnabled(memoryWriteEnabled)
                .build();
        if (!StringUtils.hasText(member.getModelOverride())) {
            childContext.setAgentModelType(parentContext.getAgentModelType());
            childContext.setAgentModelGroupId(parentContext.getAgentModelGroupId());
        }
        String original = parentExecution.getInputPrompt();
        if (projectableCycle && !temporaryChat && childPrompt.equals(memberPrompt)
                && parentExecution.getId().equals(parentContext.getExecutionId())
                && parentExecution.getTenantId().equals(parentContext.getTenantId())
                && agent.getId().equals(parentContext.getAgentId()) && agent.getId().equals(member.getAgentId())
                && StringUtils.hasText(original) && original.equals(parentContext.getInputPrompt())
                && original.equals(childExecution.getInputPrompt())
                && original.equals(compressStructuredText(original, originalPromptBudget(member)))
                && StringUtils.hasText(childExecution.getId()) && StringUtils.hasText(childExecution.getConversationId())
                && StringUtils.hasText(member.getId()) && StringUtils.hasText(member.getRoleName())
                && StringUtils.hasText(member.getRoleType())) {
            childContext.setTeamTaskContextProjection(new TeamTaskContextProjection(childExecution.getId(),
                    parentExecution.getTenantId(), agent.getId(), parentExecution.getId(), member.getId(), childExecution.getConversationId(),
                    member.getRoleName(), member.getRoleType(), childPrompt));
        }
        return childContext;
    }

    /**
     * 子执行沙箱策略：优先继承父执行策略，父策略缺失时按成员维度解析兜底
     */
    private SandboxPolicy resolveChildSandboxPolicy(Agent agent, AgentExecutionContext parentContext, AgentTeamMember member) {
        SandboxPolicy inherited = parentContext.getSandboxPolicy();
        if (inherited != null) {
            return inherited;
        }
        try {
            return sandboxPolicyResolver.resolve(agent, parentContext);
        } catch (Exception e) {
            log.warn("解析 Team 子执行沙箱策略失败: memberId={}, error={}", member.getId(), e.getMessage());
            return null;
        }
    }

    private void executeContributorsPipeline(Agent agent, AgentExecution execution,
                                             AgentExecutionContext parentContext, List<AgentTeamMember> members,
                                             int retryCount, String userInput, Map<String, Object> userOptions,
                                             String retryFeedback, List<Map<String, Object>> results,
                                             AgentTeamMember verificationOwner, boolean projectableCycle) {
        for (AgentTeamMember member : members) {
            String upstreamEvidence = buildMemberEvidencePrompt(results);
            if (isVerifiedDesktop(parentContext) && verificationOwner != null
                    && member.getId().equals(verificationOwner.getId())) {
                upstreamEvidence = buildDesktopVerificationHandoff(results, upstreamEvidence,
                        projectableCycle && retainsOriginalTask(parentContext.getInputPrompt(), member));
            }
            Map<String, Object> memberResult = executeMember(
                    agent, execution, parentContext, member, retryCount, userInput, userOptions, retryFeedback,
                    upstreamEvidence, List.of(), null, verificationOwner, projectableCycle
            ).join();
            results.add(memberResult);
            // 对等读取（审计 LA-08）：增量更新 memberId→executionId 映射，供后续成员按 can_read_peers 读取当前轮已完成队友
            updateMemberEvidenceReferences(parentContext, results);
            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)))) {
                break;
            }
        }
    }

    String buildDesktopVerificationHandoff(List<Map<String, Object>> results, String memberEvidence, boolean concise) {
        DesktopEvidence upstream = desktopUpstreamEvidence(results);
        if (!upstream.hasChecks()) return memberEvidence;
        List<String> receiptLines = upstream.lines();
        String receipts = DESKTOP_UPSTREAM_EVIDENCE_HEADER + "- " + String.join("\n- ", receiptLines) + "\n\n";
        if (concise) {
            String distinctEvidence = memberEvidence.lines()
                    .filter(line -> !receiptLines.contains(line.startsWith("- ") ? line.substring(2) : line))
                    .collect(Collectors.joining("\n"));
            return receipts + distinctEvidence;
        }
        return receipts + "若相关输入及产物无变化，直接引用这些检查结果。按回执保留 HTTPS 与 CLI 的先后。"
                + "已取得工单所需的 HTTPS 状态时，仅在任务要求复测或证据失效时再次访问。"
                + "检查完尚缺项后，按工单执行最终命令。\n\n"
                + memberEvidence;
    }

    private DesktopEvidence desktopUpstreamEvidence(List<Map<String, Object>> results) {
        if (results.isEmpty()) return DesktopEvidence.EMPTY;
        var previous = results.getLast();
        if (!AgentExecutionStatusEnum.COMPLETED.getCode()
                .equals(asText(previous.get(TeamGraphConstants.RESULT_STATUS)))) return DesktopEvidence.EMPTY;
        return conversationArchive.desktopUpstreamEvidence(asText(previous.get(TeamGraphConstants.RESULT_EXECUTION_ID)),
                asText(previous.get(TeamGraphConstants.RESULT_CONVERSATION_ID)));
    }

    private List<Map<String, Object>> executeMemberBatch(Agent agent, AgentExecution execution,
                                                         AgentExecutionContext parentContext,
                                                         List<AgentTeamMember> members,
                                                         int retryCount,
                                                         String userInput,
                                                         Map<String, Object> userOptions,
                                                         String retryFeedback,
                                                         String upstreamEvidence,
                                                         AgentTeamMember verificationOwner, boolean projectableCycle) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (AgentTeamMember member : members) {
            CompletableFuture<Map<String, Object>> future = executeMember(
                    agent, execution, parentContext, member, retryCount, userInput, userOptions, retryFeedback,
                    upstreamEvidence, List.of(), null, verificationOwner, projectableCycle
            ).exceptionally(throwable -> {
                        log.error("Team 成员并行执行异常: memberId={}, error={}", member.getId(), throwable.getMessage());
                        Map<String, Object> errorPayload = new LinkedHashMap<>();
                        errorPayload.put(TeamGraphConstants.RESULT_MEMBER_ID, member.getId());
                        errorPayload.put(TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName());
                        errorPayload.put(TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType());
                        errorPayload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                        String errorMsg = teamQualityDetector.resolveThrowableMessage(throwable);
                        errorPayload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, errorMsg);
                        return errorPayload;
                    });
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CompletableFuture<Map<String, Object>> executeMember(Agent agent, AgentExecution parentExecution,
                                                                 AgentExecutionContext parentContext,
                                                                 AgentTeamMember member,
                                                                 int retryCount,
                                                                 String userInput,
                                                                 Map<String, Object> userOptions,
                                                                 String retryFeedback,
                                                                 String upstreamEvidence,
                                                                 List<String> attachmentIds,
                                                                 MemberApprovalResume approval,
                                                                 AgentTeamMember verificationOwner,
                                                                 boolean projectableCycle) {
        return executeMember(agent, parentExecution, parentContext, member, retryCount, userInput, userOptions,
                retryFeedback, upstreamEvidence, attachmentIds, approval, verificationOwner, projectableCycle, false);
    }

    private CompletableFuture<Map<String, Object>> executeMember(Agent agent, AgentExecution parentExecution,
                                                                 AgentExecutionContext parentContext,
                                                                 AgentTeamMember member,
                                                                 int retryCount,
                                                                 String userInput,
                                                                 Map<String, Object> userOptions,
                                                                 String retryFeedback,
                                                                 String upstreamEvidence,
                                                                 List<String> attachmentIds,
                                                                 MemberApprovalResume approval,
                                                                 AgentTeamMember verificationOwner,
                                                                 boolean projectableCycle, boolean textOnly) {
        String modelName = StringUtils.hasText(member.getModelOverride()) ? member.getModelOverride() : parentExecution.getAiModel();
        String childConversationId = "team:" + parentExecution.getId() + ":" + member.getId()
                + (retryCount > 0 ? ":r" + retryCount : "");
        // 恢复只复用同一轮的暂停记录，执行权由引擎的 PAUSED → RUNNING 原子更新领取。
        AgentExecution childExecution = approval != null
                ? approval.child()
                : findResumableChildExecution(parentExecution, member.getId(), childConversationId);
        boolean resuming = childExecution != null;
        if (childExecution == null) {
            childExecution = new AgentExecution();
            childExecution.setTenantId(parentExecution.getTenantId());
            childExecution.setAgentId(agent.getId());
            childExecution.setTaskId(parentExecution.getTaskId());
            childExecution.setSpecId(parentExecution.getSpecId());
            childExecution.setParentExecutionId(parentExecution.getId());
            childExecution.setTeamMemberId(member.getId());
            childExecution.setRuntimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
            // 重试轮使用独立会话后缀，避免污染已写入历史的会话；恢复轮（retryCount=0）复用原会话
            childExecution.setConversationId(childConversationId);
            childExecution.setInputPrompt(parentExecution.getInputPrompt());
            childExecution.setInputContext(parentExecution.getInputContext());
            childExecution.setMemoryProjectId(parentExecution.getMemoryProjectId());
            childExecution.setCreatedBy(parentContext.getUserId());
            childExecution.setTemporaryChat(parentContext.isTemporaryChat());
            childExecution.setMemoryWriteEnabled(parentContext.getMemoryWriteEnabled());
            childExecution.setAiModel(modelName);
            childExecution.setStatus(AgentExecutionStatusEnum.QUEUED.getCode());
            if (!agentLogService.createTeamChildWhileParentRunning(childExecution)) {
                return CompletableFuture.completedFuture(Map.of(
                        TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.STOPPED.getCode()));
            }
        }

        AgentExecutionContext childContext = buildChildExecutionContext(agent, parentExecution, parentContext, member,
                retryCount, userInput, userOptions, retryFeedback, upstreamEvidence, childExecution, verificationOwner,
                projectableCycle && !resuming);
        childContext.setAcceptanceRepairOnly(textOnly);
        if (!attachmentIds.isEmpty()) {
            childContext.setUserId(parentExecution.getCreatedBy());
            childContext.setAttachmentIds(attachmentIds);
            childContext.setAttachmentSourceConversationId(parentExecution.getConversationId());
        }

        AgentExecutionInputDTO resumeInput = new AgentExecutionInputDTO();
        if (approval != null) {
            BeanUtils.copyProperties(approval.input(), resumeInput);
        }
        resumeInput.setMessage(userInput);
        // lambda 内引用需 effectively final：捕获本次执行实际使用的子执行
        AgentExecution capturedChild = childExecution;
        var childRun = resuming ? agentExecutionEngine.resume(childContext, resumeInput)
                : childContext.isEvaluation() ? agentExecutionEngine.executeForEvaluation(childContext)
                : agentExecutionEngine.execute(childContext);
        long timeoutSeconds = resolveMemberTimeoutSeconds(parentContext);
        return childRun
                .orTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .handle((result, throwable) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(TeamGraphConstants.RESULT_MEMBER_ID, member.getId());
                    payload.put(TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName());
                    payload.put(TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType());
                    payload.put(TeamGraphConstants.RESULT_MODEL, modelName);
                    payload.put(TeamGraphConstants.RESULT_EXECUTION_ID, capturedChild.getId());
                    if (throwable != null) {
                        if (throwable instanceof TimeoutException) {
                            stopChildExecution(capturedChild, member);
                            payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                            payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE,
                                    "成员执行超时（超过 " + timeoutSeconds + " 秒），已向子执行发送停止信号");
                            return payload;
                        }
                        String errorMessage = teamQualityDetector.resolveThrowableMessage(throwable);
                        if (teamQualityDetector.isTransientModelChannelFailure(errorMessage)) {
                            return buildTransientMemberFallbackPayload(agent, parentExecution, member,
                                    capturedChild, modelName, errorMessage, payload);
                        }
                        payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                        payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, errorMessage);
                        return payload;
                    }
                    String status = result != null && StringUtils.hasText(result.getStatus())
                            ? result.getStatus()
                            : AgentExecutionStatusEnum.FAILED.getCode();
                    payload.put(TeamGraphConstants.RESULT_STATUS, status);
                    payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT,
                            capStateMemberOutput(member, result != null ? result.getOutputResult() : null));
                    payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, result != null ? result.getErrorMessage() : null);
                    payload.put(TeamGraphConstants.RESULT_CONVERSATION_ID, result != null ? result.getConversationId() : null);
                    if (!teamQualityDetector.isSuccessfulOutputStatus(status)
                            && teamQualityDetector.isTransientModelChannelFailure(result != null ? result.getErrorMessage() : null)) {
                        return buildTransientMemberFallbackPayload(agent, parentExecution, member,
                                capturedChild, modelName, result.getErrorMessage(), payload);
                    }
                    if (StringUtils.hasText((String) payload.get(TeamGraphConstants.RESULT_OUTPUT_RESULT))) {
                        agentContextPublisher.publishAgentOutput(
                                agent.getId(),
                                member.getId(),
                                capturedChild.getId(),
                                parentExecution.getId(),
                                member.getId(),
                                member.getRoleName(),
                                status,
                                String.valueOf(payload.get(TeamGraphConstants.RESULT_OUTPUT_RESULT)),
                                parentExecution.getTenantId(),
                                result != null ? result.getConversationId() : null
                        );
                    }
                    return payload;
                });
    }

    private Map<String, Object> buildTransientMemberFallbackPayload(Agent agent,
                                                                    AgentExecution parentExecution,
                                                                    AgentTeamMember member,
                                                                    AgentExecution childExecution,
                                                                    String modelName,
                                                                    String errorMessage,
                                                                    Map<String, Object> payload) {
        String fallbackOutput = buildMemberTransientFallbackOutput(member, parentExecution, errorMessage);
        boolean finalized = agentLogService.updateExecutionStatus(childExecution.getId(),
                AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(), null, null, null, fallbackOutput);
        if (!finalized) {
            var persisted = agentExecutionMapper.selectById(childExecution.getId());
            if (persisted == null) {
                payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, errorMessage);
                return payload;
            }
            if (!AgentExecutionStatusEnum.FAILED.getCode().equals(persisted.getStatus())) {
                payload.put(TeamGraphConstants.RESULT_STATUS, persisted.getStatus());
                payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT, capStateMemberOutput(member, persisted.getOutputResult()));
                payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, persisted.getErrorMessage());
                return payload;
            }
            // 已提交失败及其终态事件后，规则草案仅作为父图的临时证据。
        }
        payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT, fallbackOutput);
        payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE,
                "模型通道异常，已生成成员级规则化兜底结果: " + safeText(errorMessage, ""));
        payload.put(TeamGraphConstants.RESULT_CONVERSATION_ID, childExecution.getConversationId());
        if (finalized) {
            agentContextPublisher.publishAgentOutput(
                    agent.getId(),
                    member.getId(),
                    childExecution.getId(),
                    parentExecution.getId(),
                    member.getId(),
                    member.getRoleName(),
                    AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                    fallbackOutput,
                    parentExecution.getTenantId(),
                    childExecution.getConversationId()
            );
        }
        log.warn("Team 成员模型通道异常，启用成员级兜底: parentExecutionId={}, childExecutionId={}, memberId={}, model={}",
                parentExecution.getId(), childExecution.getId(), member.getId(), modelName);
        return payload;
    }

    private String buildMemberTransientFallbackOutput(AgentTeamMember member, AgentExecution execution, String errorMessage) {
        String roleName = safeText(member.getRoleName(), "Team成员");
        return "## " + roleName + "｜模型通道异常兜底结果\n\n"
                + "- 状态：模型调用出现通道不可用类异常，已启用规则化兜底。\n"
                + "- 可用事实：工作流上下文、Team 编排、成员角色与链路追踪已完成装配。\n"
                + "- 角色建议：围绕当前需求补充业务目标、受众、关键卖点、执行步骤、验收标准与风险清单。\n"
                + "- 质量要求：该结果仅作为聚合节点继续运行的可复核草案，不得直接标记为客户最终稿。\n\n"
                + "---\n"
                + "- 父执行ID: " + safeText(execution.getId(), "") + "\n"
                + "- 成员ID: " + safeText(member.getId(), "") + "\n"
                + "- 兜底原因: " + compressStructuredText(errorMessage, 500);
    }


    Map<String, Object> aggregate(List<Map<String, Object>> memberResults, int retryCount) {
        // 父执行已停止：短路聚合，终态保持 stopped
        boolean stopped = memberResults.stream()
                .anyMatch(result -> AgentExecutionStatusEnum.STOPPED.getCode()
                        .equals(asText(result.get(TeamGraphConstants.RESULT_STATUS))));
        if (stopped) {
            Map<String, Object> stoppedUpdate = new LinkedHashMap<>();
            stoppedUpdate.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.STOPPED.getCode());
            return stoppedUpdate;
        }
        int pausedCount = 0;
        int successCount = 0;
        int contributorSuccessCount = 0;
        String leadOutput = null;
        List<String> memberEvidence = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<String> modelSummaries = new ArrayList<>();
        List<String> executionSummaries = new ArrayList<>();
        boolean hasContributor = false;
        boolean fallbackUsed = false;
        int deliverableResultCount = (int) memberResults.stream()
                .filter(result -> !isAdvisorRole(asText(result.get(TeamGraphConstants.RESULT_ROLE_TYPE))))
                .count();
        for (Map<String, Object> memberResult : memberResults) {
            String roleName = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME));
            String roleType = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_TYPE));
            String status = asText(memberResult.get(TeamGraphConstants.RESULT_STATUS));
            String outputResult = asText(memberResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT));
            boolean leadRole = isLeadRole(roleType);
            boolean successfulStatus = teamQualityDetector.isSuccessfulOutputStatus(status);
            boolean usableOutput = successfulStatus && StringUtils.hasText(outputResult);
            appendDelimitedSummary(modelSummaries, roleName, asText(memberResult.get(TeamGraphConstants.RESULT_MODEL)));
            appendDelimitedSummary(executionSummaries, roleName, asText(memberResult.get(TeamGraphConstants.RESULT_EXECUTION_ID)));
            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(status)) {
                pausedCount++;
            }
            if (isAdvisorRole(roleType)) {
                if (!successfulStatus && !AgentExecutionStatusEnum.PAUSED.getCode().equals(status)) {
                    warnings.add("[" + roleName + "] Advisor 未完成，已按无编排决策继续");
                }
                continue;
            }
            if (usableOutput) {
                successCount++;
                if (!leadRole) {
                    contributorSuccessCount++;
                }
            }
            if (AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode().equals(status)) {
                fallbackUsed = true;
            }
            if (successfulStatus && !usableOutput) {
                warnings.add("[" + roleName + "] 执行已完成但未产生可用输出");
            } else if (!successfulStatus && !AgentExecutionStatusEnum.PAUSED.getCode().equals(status)) {
                warnings.add("[" + roleName + "] 本轮未完成，状态: " + safeText(status, "未知"));
            }
            if (!leadRole) {
                hasContributor = true;
            }
            if (usableOutput) {
                if (leadRole) {
                    if (!StringUtils.hasText(leadOutput)) {
                        leadOutput = outputResult.trim();
                    } else {
                        warnings.add("[" + roleName + "] 检测到重复 Leader 输出，已仅保留首个有效终稿");
                    }
                } else {
                    memberEvidence.add("[" + roleName + "]\n" + compressStructuredText(
                            outputResult,
                            MAX_MEMBER_OUTPUT_CHARS
                    ));
                }
            }
            if (StringUtils.hasText(asText(memberResult.get(TeamGraphConstants.RESULT_ERROR_MESSAGE)))) {
                errors.add("[" + roleName + "] "
                        + asText(memberResult.get(TeamGraphConstants.RESULT_ERROR_MESSAGE)));
            }
        }

        Map<String, Object> update = new LinkedHashMap<>();
        if (pausedCount > 0) {
            update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.PAUSED.getCode());
            update.put(TeamGraphConstants.STATE_FINAL_ERROR, "Team Agent 等待人工输入后继续执行");
            return update;
        }
        if (successCount == 0 || !StringUtils.hasText(leadOutput) || (hasContributor && contributorSuccessCount == 0)) {
            if (teamQualityDetector.shouldUseRuleBasedFallback(errors)) {
                String fallbackOutput = buildRuleBasedFallbackOutput(memberResults, retryCount, warnings, errors,
                        modelSummaries, executionSummaries);
                update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
                update.put(TeamGraphConstants.STATE_FINAL_OUTPUT, fallbackOutput);
                update.put(TeamGraphConstants.STATE_AGGREGATE_META,
                        buildAggregateOverview(deliverableResultCount, 0, retryCount, modelSummaries, executionSummaries));
                update.put(TeamGraphConstants.STATE_AGGREGATE_WARNINGS,
                        List.of("成员模型调用全部失败，已启用规则化兜底交付，建议二次复核。"));
                return update;
            }
            update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
            List<String> failureMessages = new ArrayList<>(warnings);
            failureMessages.addAll(errors);
            if (!StringUtils.hasText(leadOutput)) {
                failureMessages.add(0, "Leader 未产出可交付终稿，Team Agent 聚合失败");
            }
            if (hasContributor && contributorSuccessCount == 0) {
                failureMessages.add(0, "除 Leader 外没有成员产出可用事实，Team Agent 聚合失败");
            }
            update.put(TeamGraphConstants.STATE_FINAL_ERROR, StringUtils.collectionToDelimitedString(failureMessages, "\n"));
            return update;
        }
        update.put(TeamGraphConstants.STATE_FINAL_STATUS, fallbackUsed || successCount < deliverableResultCount
                ? AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode()
                : AgentExecutionStatusEnum.COMPLETED.getCode());
        update.put(TeamGraphConstants.STATE_FINAL_OUTPUT, leadOutput);
        update.put(TeamGraphConstants.STATE_AGGREGATE_META,
                buildAggregateOverview(deliverableResultCount, successCount, retryCount, modelSummaries, executionSummaries));
        if (!memberEvidence.isEmpty()) {
            update.put(TeamGraphConstants.STATE_MEMBER_EVIDENCE, memberEvidence);
        }
        if (!warnings.isEmpty()) {
            update.put(TeamGraphConstants.STATE_AGGREGATE_WARNINGS, warnings);
        }
        if (!errors.isEmpty() && retryCount > 0) {
            update.put(TeamGraphConstants.STATE_FINAL_ERROR, StringUtils.collectionToDelimitedString(errors, "\n"));
        }
        return update;
    }



    private String buildRuleBasedFallbackOutput(List<Map<String, Object>> memberResults,
                                                int retryCount,
                                                List<String> warnings,
                                                List<String> errors,
                                                List<String> modelSummaries,
                                                List<String> executionSummaries) {
        List<String> roles = memberResults.stream()
                .map(memberResult -> safeText(asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME)), "未命名成员"))
                .toList();
        String roleText = StringUtils.collectionToDelimitedString(roles, "、");
        StringBuilder builder = new StringBuilder("# Team Agent 规则化兜底交付\n\n")
                .append("## 已确认事实\n")
                .append("- 本次 Team Agent 已完成编排、成员分工、上下文装配与执行追踪。\n")
                .append("- 成员角色：").append(roleText).append("。\n")
                .append("- 模型通道出现不可用类异常，系统按规则化模板生成可复核交付草案。\n\n")
                .append("## 诊断结论\n")
                .append("- 当前需求具备明确业务场景，应优先形成客户画像、关键约束、执行路径、交付清单和风险边界。\n")
                .append("- 本产物保留链路追踪信息，适合作为客户演示和二次人工复核的初稿。\n\n")
                .append("## 行动方案\n")
                .append("1. 梳理客户目标、资源约束、时间窗口和成功指标。\n")
                .append("2. 将业务闭环拆为获客/诊断/执行/交付/复盘五段，并为每段绑定负责人和验收标准。\n")
                .append("3. 使用平台上下文、工具调用、工作流节点和交付通知沉淀过程证据。\n")
                .append("4. 对最终文档执行质量复核，标记未确认假设和需补充证据。\n\n")
                .append("## 质量检查\n")
                .append("- 上下文管理：已装配 Team 成员、工作流变量和任务输入。\n")
                .append("- 工具调用：模型通道异常已记录，工作流仍保留追踪与兜底产物。\n")
                .append("- 记忆优化：后续可将本次异常和兜底策略写入 Agent 运行经验。\n")
                .append("- 交付产物：当前为规则化草案，需人工或模型恢复后增强为正式版本。\n\n")
                .append("## 风险与边界\n")
                .append("- 风险：模型 EOF 导致成员未产出深度分析，内容深度低于正常模型交付。\n")
                .append("- 建议：保留本次链路作为故障演练证据，待模型通道恢复后重新执行生成增强版。\n\n")
                .append("---\n")
                .append(buildAggregateOverview(memberResults.size(), 0, retryCount, modelSummaries, executionSummaries));
        if (!CollectionUtils.isEmpty(warnings) || !CollectionUtils.isEmpty(errors)) {
            builder.append("\n\n## 兜底原因\n");
            warnings.forEach(warning -> builder.append("- ").append(warning).append("\n"));
            errors.forEach(error -> builder.append("- ").append(error).append("\n"));
        }
        return builder.toString();
    }

    private String appendWarnings(String output, List<String> warnings) {
        String warningSection = TEAM_WARNING_TITLE + System.lineSeparator()
                + StringUtils.collectionToDelimitedString(
                warnings.stream().map(warning -> "- " + warning).toList(),
                System.lineSeparator()
        );
        if (!StringUtils.hasText(output)) {
            return warningSection;
        }
        return output + System.lineSeparator() + System.lineSeparator() + "---" + System.lineSeparator()
                + System.lineSeparator() + warningSection;
    }


    int countDetectedCompanyRows(String markdown) {
        return teamQualityDetector.countDetectedCompanyRows(markdown);
    }

    Integer resolveMinimumCompanyCount(String prompt) {
        return teamQualityDetector.resolveMinimumCompanyCount(prompt);
    }

    int countQualifiedCompanyRows(String markdown) {
        return teamQualityDetector.countQualifiedCompanyRows(markdown);
    }

    boolean containsConclusionBlocker(String markdown) {
        return teamQualityDetector.containsConclusionBlocker(markdown);
    }

    Map<String, Object> evaluateAggregateQuality(AgentExecution execution,
                                                          AgentExecutionContext context,
                                                          TeamGraphState state) {
        String finalStatus = state.finalStatus();
        if (AgentExecutionStatusEnum.PAUSED.getCode().equals(finalStatus)) {
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, "成员执行已暂停，等待人工输入"
            );
        }
        if (AgentExecutionStatusEnum.FAILED.getCode().equals(finalStatus)) {
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_FAIL,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, state.finalError()
            );
        }
        // 质量闸门评估使用全文终稿（审计 LA-01：State 中 finalOutput 已裁剪，全文从 Leader 子执行 DB 取回）
        String finalOutput = resolveFullLeadOutput(execution, state);
        if (!StringUtils.hasText(finalOutput)) {
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_FAIL,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, "Team 聚合输出为空"
            );
        }

        AgentAcceptance contract = context == null ? null : context.getAcceptance();
        if (contract != null) {
            AgentAcceptance.Verdict verdict = contract.evaluate(finalOutput, false);
            if (!verdict.passed()) {
                if (contract.canRepairWithModel() && mayRepairDelivery(context, state)) {
                    return scheduleDeliveryRepair(execution, state, "", true, verdict.explanation());
                }
                return Map.of(TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_FAIL,
                        TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, verdict.explanation());
            }
        }
        String verifier = state.value(DESKTOP_VERIFICATION_OWNER_ID, "");
        if (context != null && StringUtils.hasText(context.getDesktopVerificationCommand())
                && StringUtils.hasText(verifier) && mayRepairDelivery(context, state)
                && !agentLogService.hasCurrentDesktopVerification(context)) {
            return scheduleDeliveryRepair(execution, state, verifier, false, AgentLogService.DESKTOP_VERIFICATION_REQUIRED);
        }

        Integer minimumCompanyCount = resolveMinimumCompanyCount(context != null ? context.getInputPrompt() : null);
        if (minimumCompanyCount != null) {
            int detectedCompanyCount = countQualifiedCompanyRows(finalOutput);
            if (detectedCompanyCount < minimumCompanyCount) {
                String message = "最终主表仅检测到 " + detectedCompanyCount + " 家 high/medium 公司，低于要求的 "
                        + minimumCompanyCount + " 家，请继续补充公开线索后再交付";
                if (state.retryCount() < state.maxMemberRetries()) {
                    publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_RETRY, message);
                    return Map.of(
                            TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_RETRY,
                            TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, message
                    );
                }
                String pauseReason = "Team 聚合输出未满足最低公司数量要求: " + message;
                publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT, pauseReason);
                return Map.of(
                        TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT,
                        TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, pauseReason,
                        TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.PAUSED.getCode(),
                        TeamGraphConstants.STATE_FINAL_ERROR, pauseReason
                );
            }
            if (containsConclusionBlocker(finalOutput)) {
                String message = "最终结论仍明确标记为未满足交付条件，请继续补充公开线索后再交付";
                if (state.retryCount() < state.maxMemberRetries()) {
                    publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_RETRY, message);
                    return Map.of(
                            TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_RETRY,
                            TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, message
                    );
                }
                String pauseReason = "Team 聚合输出仍未满足交付条件: " + message;
                publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT, pauseReason);
                return Map.of(
                        TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT,
                        TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, pauseReason,
                        TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.PAUSED.getCode(),
                        TeamGraphConstants.STATE_FINAL_ERROR, pauseReason
                );
            }
        }

        QualityReflectionFeedback feedback = qualityChecker.buildImmediateFeedback(
                context, finalOutput, null, 0, buildTeamQualityParams()
        );
        if (feedback == null) {
            publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_PASS, "Team 聚合输出通过质量闸门");
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_PASS,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, "Team 聚合输出通过质量闸门"
            );
        }
        if (mayRepairDelivery(context, state) && state.memberResults().stream()
                .anyMatch(result -> isLeadRole(asText(result.get(TeamGraphConstants.RESULT_ROLE_TYPE))))) {
            return scheduleDeliveryRepair(execution, state, "", true, feedback.message());
        }
        if (state.retryCount() < state.maxMemberRetries() && state.memberResults().stream()
                .anyMatch(result -> !teamQualityDetector.isSuccessfulOutputStatus(asText(
                        result.get(TeamGraphConstants.RESULT_STATUS))))) {
            publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_RETRY, feedback.message());
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_RETRY,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, feedback.message()
            );
        }
        String pauseReason = "Team 聚合输出需人工修订: " + feedback.message();
        publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT, pauseReason);
        return Map.of(
                TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT,
                TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, pauseReason,
                TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.PAUSED.getCode(),
                TeamGraphConstants.STATE_FINAL_ERROR, pauseReason
        );
    }

    private boolean mayRepairDelivery(AgentExecutionContext context, TeamGraphState state) {
        return state.value(DELIVERY_REPAIR_ROUND, 0) == 0 && state.retryCount() < state.maxMemberRetries()
                && (context == null || context.getTeamBudget() == null || context.getTeamBudget().canStartFinalization());
    }

    private Map<String, Object> scheduleDeliveryRepair(AgentExecution execution, TeamGraphState state, String target,
                                                       boolean textOnly, String explanation) {
        publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_RETRY, explanation);
        return Map.of(TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_RETRY,
                TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, explanation, DELIVERY_REPAIR_ROUND, state.retryCount() + 1,
                DELIVERY_REPAIR_TARGET, target, DELIVERY_REPAIR_TEXT_ONLY, textOnly);
    }

    private void publishTeamQualityGateEvent(AgentExecution execution, String decision, String message) {
        String safeMessage = StringUtils.hasText(message) ? message : "Team 聚合质量闸门已执行";
        agentLogService.appendLog(
                execution.getId(),
                execution.getAgentId(),
                execution.getTenantId(),
                TeamGraphConstants.QUALITY_GATE_AWAIT_INPUT.equals(decision) ? LOG_LEVEL_WARN : LOG_LEVEL_INFO,
                AgentExecutionEventTypeEnum.QUALITY_GATE.getCode(),
                null,
                null,
                "decision=" + decision + ", message=" + safeMessage,
                null,
                null
        );
        executionEventStreamService.publish(AgentExecutionEvent.builder()
                .eventType(AgentExecutionEventTypeEnum.QUALITY_GATE.getCode())
                .executionId(execution.getId())
                .message(safeMessage)
                .payload(Map.of("decision", decision))
                .timestamp(Instant.now())
                .build());
    }

    private AgentEngineParams buildTeamQualityParams() {
        return AgentEngineParams.builder()
                .maxRounds(1)
                .maxToolCallsPerRound(1)
                .maxMessages(8)
                .maxModelRetries(1)
                .maxQualityReflections(1)
                .shadowQualityReviewEnabled(false)
                .shadowQualityReviewAwaitMillis(0)
                .maxUnexpectedToolCallRecoveries(1)
                .maxToolResultMessageLength(2000)
                .modelCallTimeoutMillis(1000)
                .fallbackEvidenceLimit(2)
                .fallbackTextLimit(120)
                .logContentLimit(240)
                .toolRequestSummaryLimit(120)
                .build();
    }

    AgentExecutionResult finalizeExecution(AgentExecution execution, AgentExecutionContext context, TeamGraphState state) {
        TokenUsageSummary tokenUsage = summarizeTeamTokenUsage(execution.getId());
        AgentExecutionStatusEnum status = Arrays.stream(AgentExecutionStatusEnum.values())
                .filter(candidate -> candidate.getCode().equals(state.finalStatus()))
                .findFirst()
                .orElse(AgentExecutionStatusEnum.FAILED);
        String finalOutput = resolveFullLeadOutput(execution, state);
        boolean fallbackReviewRequired = status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK
                && requiresFallbackReview(context);
        if (status == AgentExecutionStatusEnum.COMPLETED || status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK) {
            OutputGuardResult outputGuard = evaluateTeamOutput(execution, context, state, finalOutput);
            String guardMessage = safeText(outputGuard.message(), "Team 输出未通过安全终检");
            if (outputGuard.blocked()) {
                return finishExecution(execution, AgentExecutionStatusEnum.FAILED, guardMessage, null, tokenUsage,
                        AgentAcceptance.Status.UNVERIFIED, false);
            }
            if (outputGuard.paused()) {
                return finishExecution(execution, AgentExecutionStatusEnum.PAUSED,
                        "Team 输出安全检查暂停: " + guardMessage, finalOutput, tokenUsage,
                        AgentAcceptance.Status.UNVERIFIED, true);
            }
            finalOutput = sanitizeTeamOutput(finalOutput);
        }
        if (fallbackReviewRequired) {
            return finishExecution(execution, AgentExecutionStatusEnum.PAUSED,
                    "高风险 Team 兜底交付需人工复核后继续", finalOutput, tokenUsage,
                    AgentAcceptance.Status.UNVERIFIED, true);
        }
        boolean verificationRequired = StringUtils.hasText(context.getDesktopVerificationCommand());
        if (verificationRequired && (status == AgentExecutionStatusEnum.COMPLETED
                || status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK)
                && !agentLogService.hasCurrentDesktopVerification(context)) {
            String error = status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK
                    && allMembersHadModelChannelFailure(state.memberResults())
                    ? TEAM_MODEL_CHANNEL_UNAVAILABLE : AgentLogService.DESKTOP_VERIFICATION_REQUIRED;
            return finishExecution(execution, AgentExecutionStatusEnum.FAILED, error, null, tokenUsage,
                    context.getAcceptance() == null ? null : AgentAcceptance.Status.UNVERIFIED, false);
        }
        if (context.getAcceptance() != null) {
            if (status == AgentExecutionStatusEnum.COMPLETED || status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK) {
                AgentAcceptance.Verdict verdict = context.getAcceptance().evaluate(finalOutput,
                        status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK);
                return verdict.passed()
                        ? finishExecution(execution, status, state.finalError(), finalOutput, tokenUsage,
                                verdict.status(), fallbackReviewRequired)
                        : finishExecution(execution, AgentExecutionStatusEnum.FAILED, verdict.explanation(), null,
                                tokenUsage, verdict.status(), false);
            }
            if (status == AgentExecutionStatusEnum.FAILED) {
                return finishExecution(execution, status, state.finalError(), null,
                        tokenUsage, AgentAcceptance.Status.UNVERIFIED, false);
            }
        }
        return switch (status) {
            case PAUSED -> finishExecution(execution, AgentExecutionStatusEnum.PAUSED,
                    state.finalError(), null, tokenUsage);
            case COMPLETED -> finishExecution(execution, AgentExecutionStatusEnum.COMPLETED,
                    null, finalOutput, tokenUsage);
            case COMPLETED_WITH_FALLBACK -> finishExecution(execution, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK,
                    state.finalError(), finalOutput, tokenUsage, null, fallbackReviewRequired);
            case STOPPED -> finishExecution(execution, AgentExecutionStatusEnum.STOPPED,
                    "用户已停止执行", null, tokenUsage);
            default -> finishFailure(execution, context, state.finalError(), !verificationRequired, tokenUsage);
        };
    }

    private OutputGuardResult evaluateTeamOutput(AgentExecution execution, AgentExecutionContext context,
                                                  TeamGraphState state, String output) {
        return evaluateTeamOutput(execution, context, state.finalStatus(), state.retryCount(),
                state.memberResults(), output);
    }

    private OutputGuardResult evaluateTeamOutput(AgentExecution execution, AgentExecutionContext context,
                                                  String finalStatus, int retryCount,
                                                  List<Map<String, Object>> memberResults, String output) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimeType", AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
        metadata.put("finalStatus", finalStatus);
        metadata.put("retryCount", retryCount);
        metadata.put("memberResultCount", memberResults == null ? 0 : memberResults.size());
        if (context != null && context.getInputContext() != null) {
            copyIfPresent(context.getInputContext(), metadata, "riskLevel");
            copyIfPresent(context.getInputContext(), metadata, "highRiskTags");
        }
        return outputSecurityGuard().evaluate(new OutputGuardContext(
                execution.getTenantId(), execution.getAgentId(), execution.getId(),
                "agent_execution", execution.getId(), null, null, null,
                context != null ? context.getProjectId() : null,
                output, sanitizeTeamOutput(output), metadata
        ));
    }

    private String sanitizeTeamOutput(String output) {
        if (!StringUtils.hasText(output) || qualityChecker == null) {
            return output;
        }
        String sanitized = qualityChecker.sanitize(output);
        return StringUtils.hasText(sanitized) ? sanitized : output;
    }

    private AgentOutputSecurityGuard outputSecurityGuard() {
        if (outputSecurityGuard == null) {
            outputSecurityGuard = new AgentOutputSecurityGuard(securityRuntimeGuardServiceProvider);
        }
        return outputSecurityGuard;
    }

    private boolean requiresFallbackReview(AgentExecutionContext context) {
        if (context == null || context.getInputContext() == null) {
            return false;
        }
        Object reviewRequired = context.getInputContext().get("fallbackReviewRequired");
        if (Boolean.TRUE.equals(reviewRequired)
                || "true".equalsIgnoreCase(String.valueOf(reviewRequired))) {
            return true;
        }
        Object riskLevel = context.getInputContext().get("riskLevel");
        if (riskLevel != null && Set.of("high", "critical").contains(
                String.valueOf(riskLevel).trim().toLowerCase(Locale.ROOT))) {
            return true;
        }
        Object highRiskTags = context.getInputContext().get("highRiskTags");
        if (highRiskTags instanceof List<?> tags) {
            return tags.stream().filter(tag -> tag != null)
                    .map(tag -> String.valueOf(tag).trim().toLowerCase(Locale.ROOT))
                    .anyMatch(tag -> Set.of("high", "critical", "high_risk").contains(tag));
        }
        return false;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    private boolean allMembersHadModelChannelFailure(List<Map<String, Object>> memberResults) {
        return !memberResults.isEmpty() && memberResults.stream().allMatch(result -> {
            String status = asText(result.get(TeamGraphConstants.RESULT_STATUS));
            String error = asText(result.get(TeamGraphConstants.RESULT_ERROR_MESSAGE));
            return (AgentExecutionStatusEnum.FAILED.getCode().equals(status)
                    || AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode().equals(status))
                    && teamQualityDetector.isTransientModelChannelFailure(error);
        });
    }

    private AgentExecutionResult finishUnexpectedFailure(AgentExecution execution, Exception exception,
                                                         String phase, boolean allowFallback, AgentExecutionContext context) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        log.error("Team Agent 执行失败: executionId={}, phase={}, exceptionType={}, causeType={}",
                execution.getId(), phase, exception.getClass().getSimpleName(), cause.getClass().getSimpleName());
        var tokenUsage = new TokenUsageSummary(null, null);
        if (allowFallback && !(cause instanceof SQLException || cause instanceof DataAccessException)
                && teamQualityDetector.isTransientModelChannelFailure(exception.getMessage())) {
            return finishFallback(execution, context, "模型通道暂不可用", tokenUsage);
        }
        String error = exception instanceof TeamInputException inputError ? inputError.getMessage() : TEAM_EXECUTION_FAILED;
        return finishExecution(execution, AgentExecutionStatusEnum.FAILED, error, null, tokenUsage,
                context != null && context.getAcceptance() != null ? AgentAcceptance.Status.UNVERIFIED : null, false);
    }

    private AgentExecutionResult finishFailure(AgentExecution execution, AgentExecutionContext context,
                                               String error, boolean allowFallback,
                                               TokenUsageSummary tokenUsage) {
        if (allowFallback && teamQualityDetector.isTransientModelChannelFailure(error)) {
            return finishFallback(execution, context, error, tokenUsage);
        }
        return finishExecution(execution, AgentExecutionStatusEnum.FAILED, error, null, tokenUsage);
    }

    private AgentExecutionResult finishFallback(AgentExecution execution, AgentExecutionContext context,
                                                String reason, TokenUsageSummary tokenUsage) {
        String output = teamQualityDetector.buildExecutionLevelFallbackOutput(execution.getId(), execution.getAgentId(), reason);
        OutputGuardResult outputGuard = evaluateTeamOutput(execution, context,
                AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(), 0, List.of(), output);
        if (outputGuard.blocked()) {
            return finishExecution(execution, AgentExecutionStatusEnum.FAILED,
                    safeText(outputGuard.message(), "Team 兜底输出未通过安全终检"), null, tokenUsage,
                    AgentAcceptance.Status.UNVERIFIED, false);
        }
        if (outputGuard.paused()) {
            return finishExecution(execution, AgentExecutionStatusEnum.PAUSED,
                    "Team 兜底输出安全检查暂停: " + safeText(outputGuard.message(), "需要人工复核"),
                    output, tokenUsage, AgentAcceptance.Status.UNVERIFIED, true);
        }
        output = sanitizeTeamOutput(output);
        boolean reviewRequired = requiresFallbackReview(context);
        if (reviewRequired) {
            return finishExecution(execution, AgentExecutionStatusEnum.PAUSED,
                    "高风险 Team 兜底交付需人工复核后继续", output, tokenUsage,
                    AgentAcceptance.Status.UNVERIFIED, true);
        }
        return finishExecution(execution, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK,
                reason, output, tokenUsage, null, false);
    }

    private AgentExecutionResult finishExecution(AgentExecution execution, AgentExecutionStatusEnum status,
                                                 String error, String output, TokenUsageSummary tokenUsage) {
        return finishExecution(execution, status, error, output, tokenUsage, null, false);
    }

    private AgentExecutionResult finishExecution(AgentExecution execution, AgentExecutionStatusEnum status,
                                                 String error, String output, TokenUsageSummary tokenUsage,
                                                 AgentAcceptance.Status acceptanceStatus,
                                                 boolean reviewRequired) {
        String persistedError = status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK ? null : error;
        AgentExecutionEvent event = status == AgentExecutionStatusEnum.PAUSED
                ? null : terminalEvent(execution, status, error, output, acceptanceStatus, reviewRequired);
        BooleanSupplier transition = status == AgentExecutionStatusEnum.PAUSED
                ? () -> agentLogService.tryTransitionExecutionStatus(execution.getId(),
                        AgentExecutionStatusEnum.RUNNING.getCode(), status.getCode(), persistedError,
                        tokenUsage.inputTokens(), tokenUsage.outputTokens(), output)
                : () -> agentLogService.tryFinalizeWithEvent(execution.getId(), status.getCode(), persistedError,
                        tokenUsage.inputTokens(), tokenUsage.outputTokens(), output, acceptanceStatus,
                        event);
        boolean updated = status == AgentExecutionStatusEnum.COMPLETED || status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK
                ? conversationArchive.appendOnTransition(execution, output, transition)
                : transition.getAsBoolean();
        if (!updated) {
            return currentExecutionResult(execution);
        }
        if (status == AgentExecutionStatusEnum.COMPLETED || status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK) {
            conversationArchive.saveUserMemory(execution);
        }
        if (status != AgentExecutionStatusEnum.PAUSED) {
            discardPendingSteeringOnTerminal(execution.getId());
        }
        if (agentTraceService != null) {
            try {
                LocalDateTime endedAt = LocalDateTime.now();
                LocalDateTime startedAt = execution.getStartedAt() == null ? endedAt : execution.getStartedAt();
                agentTraceService.recordAgentSpan(execution.getId(), execution.getTenantId(), execution.getAgentId(),
                        startedAt, Duration.between(startedAt, endedAt).toMillis(),
                        tokenUsage.inputTokens() == null ? 0 : tokenUsage.inputTokens(),
                        tokenUsage.outputTokens() == null ? 0 : tokenUsage.outputTokens(), status.getCode());
            } catch (RuntimeException traceFailure) {
                log.warn("Team 追踪写入失败: executionId={}", execution.getId(), traceFailure);
            }
        }
        if (event != null) {
            var type = AgentExecutionEventTypeEnum.valueOf(event.getEventType());
            agentLogService.appendLog(execution.getId(), execution.getAgentId(), execution.getTenantId(),
                    resolveParentLogLevel(type), event.getEventType(), null, null,
                    buildParentLogContent(event.getMessage(), event.getPayload()), null, null);
        }
        if (status == AgentExecutionStatusEnum.PAUSED) {
            try {
                publishPause(execution);
            } catch (RuntimeException exception) {
                log.warn("发布 Team 暂停事件失败: executionId={}", execution.getId(), exception);
            }
        }
        return AgentExecutionResult.builder()
                .status(status.getCode())
                .errorMessage(persistedError)
                .outputResult(output)
                .conversationId(execution.getConversationId())
                .build();
    }

    private String withPreviousTurns(String prompt, AgentExecution execution, boolean temporaryChat) {
        String history = conversationArchive.previousTurns(execution, temporaryChat);
        return StringUtils.hasText(history)
                ? prompt + "\n\n## 最近会话（供理解追问）\n" + history
                : prompt;
    }

    private AgentExecutionEvent terminalEvent(AgentExecution execution, AgentExecutionStatusEnum status,
                                              String error, String output, AgentAcceptance.Status acceptance) {
        return terminalEvent(execution, status, error, output, acceptance, false);
    }

    private AgentExecutionEvent terminalEvent(AgentExecution execution, AgentExecutionStatusEnum status,
                                              String error, String output, AgentAcceptance.Status acceptance,
                                              boolean reviewRequired) {
        var type = switch (status) {
            case COMPLETED -> AgentExecutionEventTypeEnum.COMPLETED;
            case COMPLETED_WITH_FALLBACK -> AgentExecutionEventTypeEnum.FALLBACK_USED;
            case STOPPED -> AgentExecutionEventTypeEnum.CANCELLED;
            case FAILED -> AgentExecutionEventTypeEnum.FAILED;
            default -> throw new IllegalArgumentException("无效 Team 执行终态: " + status);
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        if (output != null) {
            payload.put(TeamGraphConstants.EVENT_PAYLOAD_FINAL_OUTPUT, output);
        }
        if (acceptance != null) {
            payload.put("acceptanceStatus", acceptance.code());
            if (output != null) {
                payload.put("outputSha256", AgentAcceptance.outputSha256(output));
            }
        }
        if (status == AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK) {
            payload.put("fallbackReason", safeText(error, "模型通道异常"));
            payload.put("reviewRequired", reviewRequired);
            payload.put("recommendedActions", List.of("重新运行", "转人工复核", "查看 trace"));
        }
        String message = switch (status) {
            case COMPLETED -> "Team Agent 执行完成";
            case COMPLETED_WITH_FALLBACK -> "Team Agent 已启用兜底交付";
            case STOPPED -> "Team Agent 已停止";
            default -> safeText(error, "Team Agent 执行失败");
        };
        return AgentExecutionEvent.builder().executionId(execution.getId()).eventType(type.getCode())
                .message(message).payload(payload.isEmpty() ? null : payload).timestamp(Instant.now()).build();
    }

    private void publishPause(AgentExecution execution) {
        var pending = teamChildApprovalService.findPending(execution);
        if (pending.isEmpty()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(TeamGraphConstants.EVENT_PAYLOAD_GRAPH_THREAD_ID,
                    safeText(execution.getGraphThreadId(), ""));
            payload.put(TeamGraphConstants.EVENT_PAYLOAD_CHECKPOINT_NAMESPACE,
                    safeText(execution.getCheckpointNamespace(), ""));
            publishParentEvent(execution, AgentExecutionEventTypeEnum.REQUIRE_INPUT, "Team Agent 等待人工输入",
                    payload);
            return;
        }
        var approval = pending.get().approval();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approval.getId());
        payload.put("toolCode", approval.getToolCode());
        payload.put("args", approval.getToolArgumentsText());
        payload.put("toolCommand", approval.getToolArgumentsText());
        payload.put("ioType", approval.getIoType());
        payload.put("riskLevel", approval.getRiskLevel());
        payload.put("actions", TeamChildApprovalService.ALLOWED_DECISIONS);
        payload.put("allowedDecisions", TeamChildApprovalService.ALLOWED_DECISIONS);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.APPROVAL_REQUIRED,
                "Team 成员的工具需要审批", payload);
    }

    private MemberApprovalResume resolveMemberApproval(AgentExecution execution, AgentExecutionInputDTO input) {
        if (input == null) {
            return null;
        }
        if (StringUtils.hasText(input.getSecurityPausedChildExecutionId())) {
            AgentExecution child = agentExecutionMapper.selectById(input.getSecurityPausedChildExecutionId());
            if (child == null || !execution.getId().equals(child.getParentExecutionId())
                    || !execution.getAgentId().equals(child.getAgentId())
                    || !execution.getTenantId().equals(child.getTenantId())
                    || !AgentExecutionStatusEnum.PAUSED.getCode().equals(child.getStatus())) {
                throw new TeamInputException("Team 成员的安全暂停已失效");
            }
            return new MemberApprovalResume(child, input);
        }
        if (!StringUtils.hasText(input.getApprovalDecision())) {
            return null;
        }
        var child = teamChildApprovalService.findDecided(execution, input).orElseThrow(() ->
                new TeamInputException("Team 子执行审批已失效"));
        return new MemberApprovalResume(child.execution(), input);
    }

    record MemberApprovalResume(AgentExecution child, AgentExecutionInputDTO input) {}

    private AgentExecutionResult currentExecutionResult(AgentExecution execution) {
        AgentExecution current = agentExecutionMapper.selectById(execution.getId());
        if (current == null) {
            current = execution;
        }
        return AgentExecutionResult.builder()
                .status(current.getStatus())
                .errorMessage(current.getErrorMessage())
                .outputResult(current.getOutputResult())
                .conversationId(current.getConversationId())
                .build();
    }

    /**
     * 父执行是否已被停止（复用 Solo 引擎的 stopped 状态语义：
     * 工作流编排超时/用户停止均写入 stopped 状态）。
     */
    private boolean isParentExecutionStopped(String parentExecutionId) {
        if (executionStopSignal != null) return executionStopSignal.isStopped(parentExecutionId);
        AgentExecution execution = agentExecutionMapper.selectById(parentExecutionId);
        return execution != null && AgentExecutionStatusEnum.STOPPED.getCode().equals(execution.getStatus());
    }

    /**
     * 终态（非 PAUSED）清理 pending 引导：父执行已结束不再消费，
     * 清理失败仅记录日志，不得阻断终态流程。
     */
    private void discardPendingSteeringOnTerminal(String executionId) {
        try {
            executionSteeringService.markDiscardedByExecution(executionId);
        } catch (Exception e) {
            log.warn("终态清理 pending 引导失败: executionId={}, error={}", executionId, e.getMessage());
        }
    }

    private TokenUsageSummary summarizeTeamTokenUsage(String parentExecutionId) {
        if (!StringUtils.hasText(parentExecutionId)) {
            return TokenUsageSummary.empty();
        }
        List<AgentExecution> childExecutions = agentExecutionMapper.selectList(
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getParentExecutionId, parentExecutionId));
        if (CollectionUtils.isEmpty(childExecutions)) {
            return TokenUsageSummary.empty();
        }
        long inputTokens = 0L;
        long outputTokens = 0L;
        for (AgentExecution childExecution : childExecutions) {
            inputTokens += childExecution.getTokenInput() == null ? 0L : childExecution.getTokenInput();
            outputTokens += childExecution.getTokenOutput() == null ? 0L : childExecution.getTokenOutput();
        }
        return new TokenUsageSummary(inputTokens, outputTokens);
    }

    private record TokenUsageSummary(Long inputTokens, Long outputTokens) {
        private static TokenUsageSummary empty() {
            return new TokenUsageSummary(0L, 0L);
        }
    }



    private Optional<TeamGraphState> resumeGraph(CompiledGraph<TeamGraphState> compiledGraph,
                                                 RunnableConfig runnableConfig,
                                                 AgentExecution execution,
                                                 AgentExecutionInputDTO resumeInput) throws Exception {
        publishParentEvent(execution, AgentExecutionEventTypeEnum.USER_INPUT, "收到人工输入，继续执行 Team Graph", null);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.RESUMED, "Team Agent 恢复编排执行", null);
        // 版本校验与迁移必须在恢复执行（成员/工具副作用）发生前完成（审计 LA-10）。
        RunnableConfig resumedConfig = runnableConfig;
        var checkpoint = langGraphRuntimeAdapter.lastStateOf(compiledGraph, runnableConfig);
        if (checkpoint.isPresent()) {
            TeamGraphState state = checkpoint.get();
            assertSchemaCompatible(state);
            int fromVersion = state.schemaVersion();
            if (fromVersion < TeamGraphConstants.SCHEMA_VERSION) {
                resumedConfig = compiledGraph.updateState(runnableConfig, migrateState(state, fromVersion));
            }
        }
        return langGraphRuntimeAdapter.resume(compiledGraph, resumedConfig,
                safeText(resumeInput.getMessage(), ""), resumeInput.getOptions());
    }

    private TeamGraphState resolveGraphState(Optional<TeamGraphState> finalState,
                                             CompiledGraph<TeamGraphState> compiledGraph,
                                             RunnableConfig runnableConfig,
                                             Map<String, Object> initialState) {
        TeamGraphState state;
        if (finalState.isPresent()) {
            state = finalState.get();
        } else {
            state = langGraphRuntimeAdapter.lastStateOf(compiledGraph, runnableConfig)
                    .orElseGet(() -> new TeamGraphState(initialState));
        }
        assertSchemaCompatible(state);
        return state;
    }

    /**
     * State schema 版本检查（审计 LA-10）：checkpoint 由更高版本代码写入时拒绝恢复，避免字段缺失导致静默错误。
     */
    void assertSchemaCompatible(TeamGraphState state) {
        int version = state.schemaVersion();
        if (version > TeamGraphConstants.SCHEMA_VERSION) {
            throw new IllegalStateException("Team checkpoint schema 版本 " + version
                    + " 高于当前支持的 " + TeamGraphConstants.SCHEMA_VERSION + "，请升级服务后再恢复该执行");
        }
    }

    /**
     * State schema 迁移（审计 LA-10）：旧版本写入当前版本字段，后续结构变化在此追加迁移补丁。
     */
    private Map<String, Object> migrateState(TeamGraphState state, int fromVersion) {
        log.info("Team checkpoint schema 从 v{} 迁移至 v{}", fromVersion, TeamGraphConstants.SCHEMA_VERSION);
        return Map.of(TeamGraphConstants.STATE_SCHEMA_VERSION, TeamGraphConstants.SCHEMA_VERSION);
    }

    Map<String, Object> buildInitialState(AgentExecution execution, AgentExecutionContext context,
                                           List<AgentTeamMember> members) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(TeamGraphConstants.STATE_SCHEMA_VERSION, TeamGraphConstants.SCHEMA_VERSION);
        state.put(TeamGraphConstants.STATE_RETRY_COUNT, 0);
        Map<String, List<String>> memberContexts = new LinkedHashMap<>();
        for (AgentTeamMember member : members) {
            memberContexts.put(member.getId(), List.copyOf(loadMemberSystemContexts(member)));
        }
        state.put(TeamGraphState.MEMBER_CONTEXTS, memberContexts);
        if (context.getTeamEngineParams() != null) {
            state.put(TeamGraphState.MEMBER_TIMEOUT_MINUTES, context.getTeamEngineParams().getMemberExecutionTimeoutMinutes());
            state.put(TeamGraphState.MAX_MEMBER_RETRIES, context.getTeamEngineParams().getTeamMaxMemberRetries());
        }
        state.put(TeamGraphConstants.STATE_USER_INPUT, "");
        state.put(TeamGraphConstants.STATE_USER_OPTIONS, Map.of());
        state.put(TeamGraphConstants.STATE_INPUT_PROMPT, context.getInputPrompt());
        if (isVerifiedDesktop(context)) {
            String ownerId = resolveDesktopVerificationOwnerId(members);
            if (ownerId != null) state.put(DESKTOP_VERIFICATION_OWNER_ID, ownerId);
        }
        List<String> attachmentIds = context.getAttachmentIds();
        if (!CollectionUtils.isEmpty(attachmentIds)) {
            validateAttachments(execution, attachmentIds);
            state.put(TeamGraphState.ATTACHMENT_IDS, List.copyOf(attachmentIds));
        }
        return state;
    }

    private boolean isVerifiedDesktop(AgentExecutionContext context) {
        return StringUtils.hasText(context.getDesktopVerificationCommand())
                && DesktopExecutionTarget.from(context.getInputContext()) != null;
    }

    private String resolveDesktopVerificationOwnerId(List<AgentTeamMember> members) {
        List<String> memberIds = members.stream().map(AgentTeamMember::getId).toList();
        List<AgentTeamMemberToolBinding> bindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .in(AgentTeamMemberToolBinding::getMemberId, memberIds)
                        .eq(AgentTeamMemberToolBinding::getEnabled, true));
        Set<String> membersWithOverrides = bindings.stream()
                .map(AgentTeamMemberToolBinding::getMemberId)
                .collect(Collectors.toSet());
        // 工具继承由工具会话解析。成员配置不完整时继续使用原验收提示。
        if (!membersWithOverrides.containsAll(memberIds)) return null;
        Set<String> shellMembers = bindings.stream()
                .filter(binding -> isDesktopShellBinding(binding.getSourceType(), binding.getToolCode()))
                .map(AgentTeamMemberToolBinding::getMemberId)
                .collect(Collectors.toSet());
        // Leader 在所有贡献者之后运行，其余成员按配置顺序运行。
        return members.stream()
                .filter(member -> isLeadRole(member.getRoleType()) && shellMembers.contains(member.getId()))
                .map(AgentTeamMember::getId)
                .findFirst()
                .orElseGet(() -> members.reversed().stream()
                        .map(AgentTeamMember::getId)
                        .filter(shellMembers::contains)
                        .findFirst().orElse(null));
    }

    /**
     * 附件校验（审计 LA-02）：必须保持幂等且无副作用。
     * 该校验既在初始 State 构建时调用，也会在 resume 重新执行 await_input 节点时再次调用（interruptBefore 语义），
     * 用于捕获暂停期间被撤销的附件。禁止在此方法内写入任何状态或触发外部副作用。
     */
    private void validateAttachments(AgentExecution execution, List<String> attachmentIds) {
        if (!CollectionUtils.isEmpty(attachmentIds)) {
            desktopAttachments.validateObjectKeys(attachmentIds,
                    DesktopExecutionTarget.workspaceConversation(execution.getConversationId()),
                    execution.getTenantId(), execution.getCreatedBy());
        }
    }

    private List<AgentTeamMember> loadMembers(String agentId) {
        return agentTeamMemberMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMember>()
                        .eq(AgentTeamMember::getAgentId, agentId)
                        .orderByAsc(AgentTeamMember::getSortOrder));
    }

    private void validateRuntimeConfiguration(List<AgentTeamMember> members) {
        String validationError = validateRuntimeMembers(members, loadMemberContextBindingCount(members));
        if (StringUtils.hasText(validationError)) {
            throw new TeamInputException(validationError);
        }
    }

    private static final class TeamInputException extends BusinessException {
        private TeamInputException(String message) {
            super(ResultCode.BAD_REQUEST, message);
        }
    }

    private Map<String, Long> loadMemberContextBindingCount(List<AgentTeamMember> members) {
        List<String> nonLeaderMemberIds = members.stream()
                .filter(member -> !isLeadRole(member.getRoleType()) && StringUtils.hasText(member.getId()))
                .map(AgentTeamMember::getId)
                .toList();
        if (CollectionUtils.isEmpty(nonLeaderMemberIds)) {
            return Map.of();
        }
        return agentTeamMemberContextBindingMapper.selectList(
                        new LambdaQueryWrapper<AgentTeamMemberContextBinding>()
                                .in(AgentTeamMemberContextBinding::getMemberId, nonLeaderMemberIds)
                                .eq(AgentTeamMemberContextBinding::getStatus, CommonConstant.STATUS_ACTIVE))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        AgentTeamMemberContextBinding::getMemberId,
                        java.util.stream.Collectors.counting()
                ));
    }

    String validateRuntimeMembers(List<AgentTeamMember> members, Map<String, Long> contextBindingCountMap) {
        if (CollectionUtils.isEmpty(members)) {
            return "Team Agent 未配置成员";
        }
        List<String> errors = new ArrayList<>();
        long leaderCount = members.stream().filter(member -> isLeadRole(member.getRoleType())).count();
        if (leaderCount != 1) {
            errors.add("必须且只能配置一个 Leader 角色");
        }
        errors.addAll(TeamMemberConfigurationValidator.validate(members.stream()
                .map(member -> new TeamMemberConfigurationValidator.MemberRef(member.getId(), member.getRoleName(),
                        member.getRoleType(), member.getDependsOn(), member.getCanReadPeers()))
                .toList()));
        List<String> missingContextMembers = members.stream()
                .filter(member -> !isLeadRole(member.getRoleType()))
                .filter(member -> contextBindingCountMap.getOrDefault(member.getId(), 0L) <= 0)
                .map(member -> safeText(member.getRoleName(), member.getId()))
                .toList();
        if (!missingContextMembers.isEmpty()) {
            errors.add("以下成员缺少必备上下文绑定: "
                    + StringUtils.collectionToDelimitedString(missingContextMembers, "、"));
        }
        if (errors.isEmpty()) {
            return null;
        }
        return "Team Agent 配置不完整：" + StringUtils.collectionToDelimitedString(errors, "；");
    }

    private String buildPlanSummary(Agent agent, List<AgentTeamMember> members) {
        List<String> plans = members.stream()
                .map(member -> member.getRoleName() + "(" + member.getRoleType() + ")")
                .toList();
        return "Team Agent[" + agent.getName() + "] 成员分工: " + StringUtils.collectionToDelimitedString(plans, ", ");
    }

    String buildMemberPrompt(String originalPrompt, AgentTeamMember member, int retryCount,
                             String userInput, Map<String, Object> userOptions,
                             String retryFeedback, String upstreamEvidence) {
        return buildMemberPrompt(originalPrompt, member, retryCount, userInput, userOptions, retryFeedback, upstreamEvidence, null);
    }

    private String buildMemberPrompt(String originalPrompt, AgentTeamMember member, int retryCount,
                                     String userInput, Map<String, Object> userOptions,
                                     String retryFeedback, String upstreamEvidence, String desktopHandoff) {
        StringBuilder promptBuilder = new StringBuilder();
        boolean leadRole = TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.getRoleType());
        boolean customerDeliveryContext = desktopHandoff == null && ArtifactSceneResolver.isCustomerDeliveryPrompt(originalPrompt);
        boolean contactResearchContext = desktopHandoff == null && shouldUseWebResearchHints(originalPrompt, member, upstreamEvidence);
        promptBuilder.append("你当前是 Team Agent 中的成员角色。\n");
        promptBuilder.append("角色名称: ").append(member.getRoleName()).append("\n");
        promptBuilder.append("角色类型: ").append(member.getRoleType()).append("\n");
        if (StringUtils.hasText(member.getDescription())) {
            promptBuilder.append("角色职责: ").append(member.getDescription()).append("\n");
        }
        if (retryCount > 0) {
            promptBuilder.append("这是基于反思后的再次执行，请修正前一轮问题并输出可汇总结果。\n");
            if (StringUtils.hasText(retryFeedback)) {
                promptBuilder.append("上一轮质量闸门反馈:\n")
                        .append(retryFeedback).append("\n")
                        .append("本轮必须优先解决上述缺口，不要重复提交同样的问题。\n");
            }
        }
        if (StringUtils.hasText(userInput)) {
            promptBuilder.append("人工补充输入:\n").append(userInput).append("\n");
        }
        if (!CollectionUtils.isEmpty(userOptions)) {
            promptBuilder.append("人工补充选项:\n").append(userOptions).append("\n");
        }
        promptBuilder.append("事实约束:\n")
                .append("- 如果系统已确认事实块已经给出明确值，禁止继续写成“待确认”“可能”“推测”。\n")
                .append("- 仅使用与原始任务直接相关的已确认事实，不列出无关的记忆或上游证据。\n");
        if (customerDeliveryContext) {
            promptBuilder.append("- 客户交付场景中，未知项统一放入“风险与边界”或“待确认事项”，不要写内部缺失占位语或回填提示。\n");
        } else {
            promptBuilder.append("- 用户指定了缺失值的写法时直接使用该写法。其余缺失值按实际来源说明，例如“仓库中未发现”或“当前步骤未生成”。\n");
        }
        promptBuilder.append("工具使用要求:\n");
        if (desktopHandoff != null) {
            promptBuilder.append(desktopHandoff);
        } else if (contactResearchContext) {
            promptBuilder.append("- 优先使用 web.fetch 直接抓取官网首页、产品页、联系页，不要依赖 Bash 拼接 curl。\n")
                    .append("- 若 web.fetch 返回 links/emails/phones 等结构化字段，优先复用这些字段继续下钻，减少重复猜测 URL。\n")
                    .append("- 搜索引擎结果页只能用于发现候选，不能作为最终证据；若官网已可访问，优先使用官网证据。\n");
        } else {
            promptBuilder.append("- 优先复用上游已确认事实、流程上下文和已生成产物，不要脱离当前场景重新发散搜索。\n")
                    .append("- 若必须补充证据，先读取与当前任务直接相关的文档、产物或业务资料，再决定是否扩展外部检索。\n")
                    .append("- 工具调用只服务于补足关键事实，不要让搜索过程淹没最终交付。\n");
        }
        if (StringUtils.hasText(upstreamEvidence)) {
            promptBuilder.append("上游成员证据:\n")
                    .append(upstreamEvidence).append("\n");
            if (desktopHandoff != null) {
                promptBuilder.append("- 区分上游计划与已完成操作。结论须有实际文件或工具回执支持。\n");
            } else if (leadRole) {
                promptBuilder.append("引用规则:\n")
                        .append("- 用与原始任务直接相关的“上游成员证据”核对结论。仅在交付需要时引用证据。\n")
                        .append("- 若上游成员的工具执行结果已明确成功或失败，禁止在终稿中写成相反结论。\n")
                        .append("- 上游成员已标记“未公开确认”的字段，只能原样保留，不得补造联系人或联系方式。\n");
            } else {
                promptBuilder.append("协作规则:\n")
                        .append("- 优先围绕上游已确认的公司、证据和缺失字段继续补充，不要重新从零搜索。\n")
                        .append("- 若上游已给出候选公司列表，先补足这些公司的联系人、联系页和证据，再决定是否扩展新公司。\n")
                        .append("- 对已确认失败或封禁的站点，不要在同一轮继续重复抓取。\n");
            }
        }
        if (leadRole) {
            promptBuilder.append("输出要求:\n")
                    .append("- 优先严格遵循原始任务指定的回答格式和范围。用户只要求一个结果或一个缺失标记时，只返回该值。\n");
            if (customerDeliveryContext) {
                promptBuilder.append("- 用户未指定格式时，输出面向客户或业务负责人的最终交付 Markdown，至少包含“已确认事实”“交付方案”“交付清单/执行步骤”“预期业务价值”“风险与边界”。\n")
                        .append("- 不要输出仓库审计术语、工具过程、回填说明或其他内部表达。\n");
            } else {
                promptBuilder.append("- 用户未指定格式且需要交付文档时，可用 Markdown 组织“已确认事实”“结论”“关键证据”“风险与未知项”。\n");
            }
            promptBuilder.append("- 以最终交付文档正文开头，不要输出“Based on the upstream...”“我将开始汇总”之类的过程性说明。\n")
                    .append("- 直接引用系统已确认事实中的模型、流程配置和证据，不要重复写待确认。\n")
                    .append("- 控制输出体量，尽量不超过 ").append(MAX_LEAD_OUTPUT_CHARS).append(" 个字符。\n");
        } else {
            promptBuilder.append("输出要求:\n")
                    .append("- 只需汇总单值时，只提供原始任务要求的值，缺失时使用用户指定的标记。\n");
            if (customerDeliveryContext) {
                promptBuilder.append("- 只输出支撑客户交付所需的事实、证据、执行建议与风险，优先使用短列表。\n")
                        .append("- 不要输出仓库审计术语、工具过程或内部占位语。\n");
            } else {
                promptBuilder.append("- 只输出支撑汇总所需的事实、证据、风险，优先使用短列表。\n");
            }
            promptBuilder.append("- 不要生成长篇背景描述，控制输出体量，尽量不超过 ")
                    .append(MAX_MEMBER_OUTPUT_CHARS)
                    .append(" 个字符。\n");
        }
        promptBuilder.append("\n原始任务:\n")
                .append(compressStructuredText(originalPrompt, originalPromptBudget(member)));
        return promptBuilder.toString();
    }

    private int originalPromptBudget(AgentTeamMember member) {
        return TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.getRoleType())
                ? MAX_LEAD_PROMPT_CHARS : MAX_MEMBER_PROMPT_CHARS;
    }

    private boolean retainsOriginalTask(String originalPrompt, AgentTeamMember member) {
        return StringUtils.hasText(originalPrompt)
                && originalPrompt.equals(compressStructuredText(originalPrompt, originalPromptBudget(member)));
    }

    String buildDesktopVerifierPrompt(String originalPrompt, AgentTeamMember member, String upstreamEvidence) {
        boolean lead = isLeadRole(member.getRoleType());
        StringBuilder prompt = new StringBuilder("角色名称: ").append(member.getRoleName())
                .append("\n角色类型: ").append(member.getRoleType()).append("\n");
        if (StringUtils.hasText(member.getDescription())) {
            prompt.append("角色职责: ").append(member.getDescription()).append("\n");
        }
        prompt.append("""

                ## 最终验收
                - 复用未变化输入及产物的有效结果，成组执行独立检查。补齐工单尚缺的步骤，成员增补的测试变体仅为建议。
                - Git、HTTP 和 CLI 结论依据实际设备回执。保留 HTTPS 与 CLI 的实际先后，证据失效或工单要求复测时再查。
                - 按工单核对 README 的构建与运行说明并保留回执。若最终构建须为最后一次 Shell，先完成其他 Shell 检查。预留一轮独立执行配置的最终验收命令。成功后停止 Shell 和写入。
                - 按工单格式先报告实际结果、所需退出码和产物相对路径。从最终 Maven 回执取测试汇总，缺失时读取报告。逐项标明尚缺或失败，并遵循工单指定的缺失值写法。
                """);
        if (lead) {
            prompt.append("- 直接输出交付正文。工单要求分工时，按证据简述各角色的实际产出。\n");
        }
        return prompt.append("- 输出控制在 ").append(lead ? MAX_LEAD_OUTPUT_CHARS : MAX_MEMBER_OUTPUT_CHARS)
                .append(" 个字符内。\n\n").append(upstreamEvidence)
                .append("\n\n原始任务:\n").append(originalPrompt).toString();
    }

    private String buildDesktopHandoff(AgentTeamMember member, List<AgentToolBinding> toolBindings,
                                       AgentTeamMember verificationOwner, boolean ownsVerification) {
        String handoff = """
                - 成员按配置顺序执行，当前角色只处理职责范围内尚缺的步骤，优先复用上游文件和回执。
                - 批量处理彼此独立的检查或读取。只在结果不确定、文件已变化或验收要求复测时重复操作。
                - 当前职责只需提炼需求且事实已在工单中给全时，直接给出可执行的验收条目。工具查询无匹配后，仅在另有具体证据来源时查询其他工具。
                - 交接仅列改动的相对路径、已完成检查的实际结果，以及尚缺或失败的验收项。环境版本经确认且未变时直接复用。
                """;
        if (isLeadRole(member.getRoleType())) {
            handoff += "- Leader 在成员之后汇总交付，按原任务逐项引用实际回执，优先使用最终验收结果。"
                    + "用户要求角色分工时，简述各角色的实际产出。仅有成员状态或计划时，标明该项未验证。\n";
        }
        if (ownsVerification) {
            return handoff + "- 你负责最终验收。优先完成剩余检查和写入，预留一轮独立执行配置的最终验收命令。"
                    + "优先复用上游有效产物。"
                    + "如果工单要求最终命令是最后一次 Shell 操作，就在它之前完成 CLI 等产物检查并记录回执。"
                    + "工单要求 README 说明构建和运行时，在最终命令前核对这两段并保留内容回执。"
                    + "收到所需的网络、Git 和 CLI 结果后逐项记录，只有该证据已失效或要求复测时才重新执行。"
                    + "使用最终 Maven 回执中已核实的测试汇总，只有汇总缺失时才用文件读取工具查看已有报告。"
                    + "收到成功回执后直接输出交付结果，停止 Shell 和写入。"
                    + "把实际验收结果放在输出开头，用短行列出任务要求的构建退出码、测试数量、"
                    + "产物相对路径、CLI、Git 或网络结果。逐项注明失败或未验证。\n";
        }
        if (verificationOwner != null) {
            return handoff + "- 最终验收由 " + verificationOwner.getRoleName() + " 完成。"
                    + "请按本角色职责交接检查结果。已收到最终验收回执时，仅用现有证据汇总。\n";
        }
        if (toolBindings == null || toolBindings.stream().anyMatch(binding ->
                isDesktopShellBinding(binding.getSourceType(), binding.getToolCode()))) {
            return handoff + "- 当前角色若负责最终验收，先完成尚缺检查和写入，再按本机工具指令执行最终验收命令并报告退出码。\n";
        }
        return handoff + "- 将需要 Shell 的检查交给具备本机 Shell 的成员。\n";
    }

    private boolean isDesktopShellBinding(String sourceType, String toolCode) {
        return SourceTypeEnum.BUILTIN == SourceTypeEnum.fromCode(sourceType) && "sys.bash".equals(toolCode);
    }

    private void updateMemberEvidenceReferences(AgentExecutionContext context, List<Map<String, Object>> results) {
        List<String> executionIds = new ArrayList<>();
        Map<String, String> executionByMember = new LinkedHashMap<>();
        for (Map<String, Object> result : results) {
            if (!teamQualityDetector.isSuccessfulOutputStatus(asText(result.get(TeamGraphConstants.RESULT_STATUS)))) {
                continue;
            }
            String memberId = asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID));
            String executionId = asText(result.get(TeamGraphConstants.RESULT_EXECUTION_ID));
            if (StringUtils.hasText(memberId) && StringUtils.hasText(executionId)) {
                executionByMember.putIfAbsent(memberId, executionId);
                executionIds.add(executionId);
            }
        }
        context.setTeamMemberEvidenceIds(executionIds.stream().distinct().toList());
        context.setTeamMemberExecutionByMemberId(executionByMember);
    }

    private boolean hasPausedMemberResult(List<Map<String, Object>> memberResults) {
        if (CollectionUtils.isEmpty(memberResults)) {
            return false;
        }
        return memberResults.stream()
                .map(memberResult -> asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)))
                .anyMatch(AgentExecutionStatusEnum.PAUSED.getCode()::equals);
    }

    String buildLeadEvidencePrompt(List<Map<String, Object>> memberResults, AgentExecutionContext context,
                                   AgentTeamMember verificationOwner) {
        if (!isVerifiedDesktop(context) || verificationOwner == null) {
            return buildMemberEvidencePrompt(memberResults);
        }
        // 优先保留验收负责人实际交接，避免排在长需求和编码摘要之后被截断。
        List<Map<String, Object>> prioritized = memberResults.stream()
                .sorted(Comparator.comparing(result -> !verificationOwner.getId().equals(
                        asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID)))))
                .toList();
        return buildMemberEvidencePrompt(prioritized, verificationOwner.getId());
    }

    String buildLeadDeliveryEvidence(List<Map<String, Object>> memberResults, AgentExecutionContext context,
                                     AgentTeamMember lead, AgentTeamMember verificationOwner) {
        return buildLeadDeliveryEvidence(memberResults, context, lead, verificationOwner, false);
    }

    private String buildLeadDeliveryEvidence(List<Map<String, Object>> memberResults, AgentExecutionContext context,
                                             AgentTeamMember lead, AgentTeamMember verificationOwner, boolean concise) {
        String evidence = buildLeadEvidencePrompt(memberResults, context, verificationOwner);
        if (!isVerifiedDesktop(context) || verificationOwner == null) {
            return evidence;
        }
        if (verificationOwner.getId().equals(lead.getId())) {
            return buildDesktopVerificationHandoff(memberResults, evidence, concise);
        }
        var verifierResult = memberResults.reversed().stream().filter(result ->
                verificationOwner.getId().equals(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID)))
                        && AgentExecutionStatusEnum.COMPLETED.getCode()
                                .equals(asText(result.get(TeamGraphConstants.RESULT_STATUS)))
                        && StringUtils.hasText(asText(result.get(TeamGraphConstants.RESULT_OUTPUT_RESULT)))
                        && !StringUtils.hasText(asText(result.get(TeamGraphConstants.RESULT_ERROR_MESSAGE))))
                .findFirst().orElse(null);
        if (verifierResult == null || !agentLogService.hasCurrentDesktopVerification(context)) {
            return evidence;
        }
        var verified = conversationArchive.desktopVerificationEvidence(
                asText(verifierResult.get(TeamGraphConstants.RESULT_EXECUTION_ID)),
                asText(verifierResult.get(TeamGraphConstants.RESULT_CONVERSATION_ID)),
                context.getDesktopVerificationCommand());
        var earlierMembers = memberResults.subList(0, memberResults.indexOf(verifierResult));
        verified = verified.withMissingChecksFrom(desktopUpstreamEvidence(earlierMembers));
        List<String> archivedEvidence = verified.lines();
        String deviceEvidence = archivedEvidence.isEmpty() ? "" : "设备归档的实际验收回执\n- "
                + String.join("\n- ", archivedEvidence) + "\n\n";
        return """
                ## 当前交付验收
                服务端已核验本次工作区最后的配置验收命令存在有效成功回执。
                按工单逐项引用下方实际回执。命令、退出码、HTTP 状态行等直接引用观测值，避免重复读取或检查已有证据的项。
                已有 Git、CLI 或 HTTPS 回执的项目直接汇总，禁止读取 .git 来重复推导已有结论。
                若工单要求最终构建是最后一次 Shell 操作，单独标明这条命令。验收项目列表按类别展示即可，列表次序不代表执行先后。
                用实际回执区分较早的 HTTPS 验证和后续复查。角色分工列各自产出，只有回执证明先后时才写操作顺序。早期打包与最后一次构建分开说明。
                CLI 逐条列已收到回执的命令、退出码和输出；成员摘要中的其他案例注明回执未随交接保留，不根据单元测试推断外部 CLI 结果。
                README 有实际内容回执时，直接引用其中的构建和运行命令。文件清单仅证明路径存在。
                按工单要求判断验收是否完成。
                引用上游成员的检查时按验收交接确认适用性，若产物随后改变就标明检查针对早期版本。
                对原任务中仍缺必要证据的项，先指出具体缺口。仅当工单顺序允许时补查，否则与结论一并标明未验证。

                """ + deviceEvidence + evidence;
    }

    private String buildMemberEvidencePrompt(List<Map<String, Object>> memberResults) {
        return buildMemberEvidencePrompt(memberResults, null);
    }

    private String buildMemberEvidencePrompt(List<Map<String, Object>> memberResults, String verificationOwnerId) {
        if (CollectionUtils.isEmpty(memberResults)) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 上游成员已确认事实与证据\n");
        for (Map<String, Object> memberResult : memberResults) {
            String roleType = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_TYPE));
            if (isLeadRole(roleType) || isAdvisorRole(roleType)) {
                continue;
            }
            String roleName = safeText(asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME)), "未命名成员");
            String status = safeText(asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)), "unknown");
            String executionId = asText(memberResult.get(TeamGraphConstants.RESULT_EXECUTION_ID));
            String outputResult = asText(memberResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT));
            String errorMessage = asText(memberResult.get(TeamGraphConstants.RESULT_ERROR_MESSAGE));
            int outputBudget = verificationOwnerId != null && verificationOwnerId.equals(
                    asText(memberResult.get(TeamGraphConstants.RESULT_MEMBER_ID)))
                    ? MAX_DESKTOP_VERIFIER_EVIDENCE_CHARS : MAX_MEMBER_OUTPUT_CHARS;
            builder.append("### ").append(roleName).append("\n")
                    .append("- 执行状态: ").append(status).append("\n");
            if (StringUtils.hasText(executionId)) {
                builder.append("- 执行ID: ").append(executionId).append("\n");
            }
            if (StringUtils.hasText(outputResult)) {
                builder.append(compressStructuredText(outputResult, outputBudget)).append("\n");
            }
            if (StringUtils.hasText(errorMessage)) {
                builder.append("- 错误: ").append(errorMessage).append("\n");
            }
            builder.append("\n");
        }
        return compressStructuredText(builder.toString().trim(), MAX_LEAD_EVIDENCE_CHARS);
    }

    private int resolveChildMaxRounds(AgentExecutionContext parentContext, AgentTeamMember member, boolean ownsVerification) {
        boolean leadRole = isLeadRole(member.getRoleType());
        int parentMaxRounds = parentContext != null && parentContext.getMaxRounds() > 0
                ? parentContext.getMaxRounds() : 0;
        if (ownsVerification) {
            return parentMaxRounds == 0 ? DESKTOP_VERIFICATION_MAX_ROUNDS
                    : Math.min(parentMaxRounds, DESKTOP_VERIFICATION_MAX_ROUNDS);
        }
        int target = leadRole ? DEFAULT_CHILD_LEAD_MAX_ROUNDS : DEFAULT_CHILD_MEMBER_MAX_ROUNDS;
        int minimum = leadRole ? MIN_CHILD_LEAD_MAX_ROUNDS : MIN_CHILD_MEMBER_MAX_ROUNDS;
        if (parentMaxRounds > 0) {
            target = Math.min(parentMaxRounds, target);
        }
        return Math.max(target, minimum);
    }

    private int resolveChildMaxToolCalls(AgentExecutionContext parentContext, AgentTeamMember member,
                                         boolean ownsVerification) {
        int parentMaxToolCalls = parentContext != null && parentContext.getMaxToolCallsPerRound() > 0
                ? parentContext.getMaxToolCallsPerRound() : 0;
        int target = isLeadRole(member.getRoleType()) ? DEFAULT_CHILD_LEAD_MAX_TOOL_CALLS : DEFAULT_CHILD_MEMBER_MAX_TOOL_CALLS;
        if (ownsVerification) {
            target = DESKTOP_VERIFICATION_MAX_TOOL_CALLS;
        }
        if (parentMaxToolCalls > 0) {
            target = Math.min(parentMaxToolCalls, target);
        }
        return Math.max(target, 1);
    }

    private int resolveChildMaxMessages(AgentExecutionContext parentContext, int maxRounds, int maxToolCalls) {
        int target = 2 + maxRounds * (maxToolCalls + 2);
        if (parentContext != null && parentContext.getMaxMessages() > 0) {
            target = Math.min(parentContext.getMaxMessages(), target);
        }
        return Math.max(MIN_CHILD_MAX_MESSAGES, Math.min(target, MAX_CHILD_MAX_MESSAGES));
    }














    private String buildAggregateOverview(int totalMembers,
                                          int successCount,
                                          int retryCount,
                                          List<String> modelSummaries,
                                          List<String> executionSummaries) {
        StringBuilder builder = new StringBuilder(TEAM_METADATA_TITLE)
                .append(System.lineSeparator())
                .append("- 成员总数: ").append(totalMembers).append(System.lineSeparator())
                .append("- 成功成员数: ").append(successCount).append(System.lineSeparator())
                .append("- 本轮重试次数: ").append(retryCount).append(System.lineSeparator());
        if (!modelSummaries.isEmpty()) {
            builder.append("- 成员模型: ")
                    .append(StringUtils.collectionToDelimitedString(modelSummaries, "; "))
                    .append(System.lineSeparator());
        }
        if (!executionSummaries.isEmpty()) {
            builder.append("- 成员执行ID: ")
                    .append(StringUtils.collectionToDelimitedString(executionSummaries, "; "))
                    .append(System.lineSeparator());
        }
        builder.append("- 聚合策略: 主负责人输出优先，其他成员输出按预算压缩后保留关键证据。");
        return builder.toString();
    }

    private void appendDelimitedSummary(List<String> summaries, String roleName, String value) {
        if (summaries == null || !StringUtils.hasText(roleName) || !StringUtils.hasText(value)) {
            return;
        }
        summaries.add(roleName + "=" + value);
    }

    private boolean isLeadRole(String roleType) {
        return TeamMemberRoleTypeEnum.LEAD_AGENT.matches(roleType);
    }

    private boolean isAdvisorRole(String roleType) {
        return TeamMemberRoleTypeEnum.ADVISOR.matches(roleType);
    }

    private String compressStructuredText(String text, int budget) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
        if (normalized.length() <= budget) {
            return normalized;
        }
        List<String> segments = List.of(normalized.split("\\n\\n"));
        StringBuilder builder = new StringBuilder();
        for (String segment : segments) {
            String compacted = segment.trim();
            if (!StringUtils.hasText(compacted)) {
                continue;
            }
            String candidate = compacted.length() > Math.max(180, budget / 2)
                    ? compacted.substring(0, Math.max(180, budget / 2)) + "..."
                    : compacted;
            if (builder.length() + candidate.length() + 2 > budget) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append("\n\n");
            }
            builder.append(candidate);
        }
        if (builder.isEmpty()) {
            builder.append(normalized, 0, Math.min(normalized.length(), budget));
        }
        builder.append("\n...[内容已压缩，原始 ").append(normalized.length()).append(" 字符]");
        return builder.toString();
    }

    /**
     * State 大小控制（审计 LA-01）：所有成员（含 Leader）的完整输出仅经 read_team_member_evidence 或
     * finalizeExecution 从 DB 层读取，State 中的副本裁剪到 {@link #MAX_STATE_MEMBER_OUTPUT_CHARS}，
     * 避免 checkpoint 随 memberResults 与 finalOutput 膨胀。Leader 全文终稿在 finalizeExecution 从子执行 DB 记录取回。
     */
    String capStateMemberOutput(AgentTeamMember member, String output) {
        if (!StringUtils.hasText(output)) {
            return output;
        }
        if (output.length() <= MAX_STATE_MEMBER_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_STATE_MEMBER_OUTPUT_CHARS)
                + "\n...[State 存储已截断，原始 " + output.length()
                + " 字符，完整内容可用 read_team_member_evidence 读取]";
    }

    /**
     * 取回 Leader 全文终稿（审计 LA-01）：State 中 Leader outputResult/finalOutput 已裁剪到上限，
     * 全文从 Leader 子执行 DB 记录（output_result）取回，供质量闸门与 finalizeExecution 使用；
     * 无 Leader 或 DB 缺失时回退到 State 中的裁剪副本。
     */
    private String resolveFullLeadOutput(AgentExecution execution, TeamGraphState state) {
        String stateOutput = state.finalOutput();
        Map<String, Object> selectedLead = state.memberResults().stream()
                .filter(result -> isLeadRole(asText(result.get(TeamGraphConstants.RESULT_ROLE_TYPE))))
                .filter(result -> teamQualityDetector.isSuccessfulOutputStatus(
                        asText(result.get(TeamGraphConstants.RESULT_STATUS))))
                .filter(result -> StringUtils.hasText(asText(result.get(TeamGraphConstants.RESULT_OUTPUT_RESULT))))
                .findFirst().orElse(null);
        if (selectedLead == null || !StringUtils.hasText(stateOutput)
                || !stateOutput.contains("\n...[State 存储已截断")) {
            return stateOutput;
        }
        String leadExecutionId = asText(selectedLead.get(TeamGraphConstants.RESULT_EXECUTION_ID));
        if (!StringUtils.hasText(leadExecutionId)) {
            throw new IllegalStateException("Leader 全文终稿缺少子执行引用，拒绝交付截断内容");
        }
        try {
            AgentExecution leadChild = agentExecutionMapper.selectById(leadExecutionId);
            if (leadChild != null && StringUtils.hasText(leadChild.getOutputResult())) {
                return leadChild.getOutputResult();
            }
        } catch (RuntimeException e) {
            throw new IllegalStateException("Leader 全文终稿读取失败，拒绝交付截断内容: executionId="
                    + execution.getId() + ", leadExecutionId=" + leadExecutionId, e);
        }
        throw new IllegalStateException("Leader 全文终稿缺失，拒绝交付截断内容: executionId="
                + execution.getId() + ", leadExecutionId=" + leadExecutionId);
    }

    /**
     * 成员执行超时看门狗（审计 LA-05）：父级 orTimeout 秒数 = 成员 deadline 秒数 + 宽限 {@link #MEMBER_TIMEOUT_GRACE_SECONDS}。
     * 子执行内部已按同一 deadline 强制收敛，此处仅在子执行仍阻塞于单次长调用、未按 deadline 返回时兜底取消。
     */
    long resolveMemberTimeoutSeconds(AgentExecutionContext parentContext) {
        long minutes = parentContext != null && parentContext.getTeamEngineParams() != null
                ? parentContext.getTeamEngineParams().getMemberExecutionTimeoutMinutes() : 10L;
        return minutes * 60L + MEMBER_TIMEOUT_GRACE_SECONDS;
    }

    /**
     * 超时后向子执行写停止信号：子执行引擎在多个循环检查点轮询 isStopped，收到信号后停止后续轮次/工具调用，
     * 避免僵尸子执行继续消耗模型与工具资源。
     */
    private void stopChildExecution(AgentExecution childExecution, AgentTeamMember member) {
        if (executionStopSignal == null || childExecution == null || !StringUtils.hasText(childExecution.getId())) {
            return;
        }
        executionStopSignal.publishAfterCommit(childExecution.getId());
        log.warn("成员执行超时，已发送停止信号: parentExecutionId={}, childExecutionId={}, memberId={}",
                childExecution.getParentExecutionId(), childExecution.getId(), member.getId());
    }

    private Map<String, List<AgentToolBinding>> loadMemberToolBindings(String tenantId, String agentId, List<String> memberIds) {
        if (memberIds.isEmpty()) return Map.of();
        var memberBindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .in(AgentTeamMemberToolBinding::getMemberId, memberIds)
                        .eq(AgentTeamMemberToolBinding::getEnabled, true)
                        .orderByAsc(AgentTeamMemberToolBinding::getPriority));
        Map<String, List<AgentToolBinding>> byMember = new LinkedHashMap<>();
        for (AgentTeamMemberToolBinding binding : memberBindings) {
            if (!StringUtils.hasText(binding.getMemberId())) continue;
            AgentToolBinding tool = new AgentToolBinding();
            tool.setId(binding.getId());
            tool.setTenantId(tenantId);
            tool.setAgentId(agentId);
            tool.setToolCode(binding.getToolCode());
            tool.setSourceType(binding.getSourceType());
            tool.setSourceRefId(binding.getSourceRefId());
            tool.setEnabled(binding.getEnabled());
            tool.setPriority(binding.getPriority());
            tool.setConfigOverride(binding.getConfigOverride());
            byMember.computeIfAbsent(binding.getMemberId(), ignored -> new ArrayList<>()).add(tool);
        }
        byMember.replaceAll((id, bindings) -> List.copyOf(bindings));
        return Map.copyOf(byMember);
    }

    private List<String> memberSystemContexts(AgentExecutionContext parentContext, AgentTeamMember member) {
        Map<String, List<String>> snapshot = parentContext.getTeamMemberSystemContexts();
        List<String> contexts = snapshot != null && snapshot.containsKey(member.getId())
                ? snapshot.get(member.getId()) : loadMemberSystemContexts(member);
        if (parentContext.getAcceptance() == null || !isLeadRole(member.getRoleType())) {
            return contexts;
        }
        List<String> withAcceptance = new ArrayList<>(contexts);
        withAcceptance.add(parentContext.getAcceptance().prompt());
        return withAcceptance;
    }

    private List<String> loadMemberSystemContexts(AgentTeamMember member) {
        List<String> contexts = new ArrayList<>();
        contexts.add("## Team 成员角色说明\n请仅承担当前角色职责，并输出便于团队汇总的阶段性结果。");
        if (TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.getRoleType())) {
            contexts.add("""
                    ## Lead Agent（团队负责人）约束
                    你承担最终整合与交付职责，而非直接展开所有细节实现：
                    - 汇总所有成员的上游证据，识别互相矛盾、重复或缺失的事实；必要时用 read_team_member_evidence 工具按页读取成员完整交付。
                    - 上游证据存在缺口时，在终稿中明确标注尚缺项，禁止臆造结论。
                    - 最终交付前做整体复核，确保结论与上游工具回执一致、未确认项已显式标注。
                    """);
        }
        if (TeamMemberRoleTypeEnum.ADVISOR.matches(member.getRoleType())) {
            contexts.add("""
                    ## Advisor（团队协调顾问）约束
                    你在贡献者之后、Lead 之前协调团队结果，不直接撰写最终交付：
                    - 审查成员结果是否矛盾、重复或缺失，必要时用 read_team_member_evidence 读取完整证据。
                    - 仅在确需调整编排时输出决策指令，每个指令独占一行并放在输出开头：
                      TEAM_ADVISOR SKIP <memberId> <跳过该成员结果的原因>
                      TEAM_ADVISOR APPEND <memberId> <要求该成员追加执行的原因>
                    - 只能引用当前团队已配置的普通贡献者，不能引用 Lead 或 Advisor。
                    - APPEND 仅用于尚未成功的成员，禁止要求已成功成员重复执行；同一成员最多追加一次。
                    - 无调整必要时不要输出任何 TEAM_ADVISOR 指令，只输出简短协调摘要。
                    """);
        }
        List<AgentTeamMemberContextBinding> bindings = agentTeamMemberContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberContextBinding>()
                        .eq(AgentTeamMemberContextBinding::getMemberId, member.getId())
                        .eq(AgentTeamMemberContextBinding::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByAsc(AgentTeamMemberContextBinding::getSortOrder));
        for (AgentTeamMemberContextBinding binding : bindings) {
            if (StringUtils.hasText(binding.getContent())) {
                contexts.add("## 成员上下文: " + safeText(binding.getTitle(), "未命名上下文") + "\n" + binding.getContent());
                continue;
            }
            if (!StringUtils.hasText(binding.getContextId())) {
                continue;
            }
            List<ContextItem> contextItems = contextItemMapper.selectList(
                    new LambdaQueryWrapper<ContextItem>()
                            .eq(ContextItem::getContextId, binding.getContextId())
                            .orderByAsc(ContextItem::getSortOrder)
                            .orderByAsc(ContextItem::getCreatedAt));
            String content = contextItems.stream()
                    .map(ContextItem::getContent)
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> left + "\n\n" + right)
                    .orElse("");
            if (StringUtils.hasText(content)) {
                contexts.add("## 成员上下文: " + safeText(binding.getTitle(), "未命名上下文") + "\n" + content);
            }
        }
        return contexts;
    }

    private void persistGraphMetadata(String executionId, String threadId, String checkpointNamespace) {
        AgentExecution update = new AgentExecution();
        update.setId(executionId);
        update.setRuntimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
        update.setGraphThreadId(threadId);
        update.setCheckpointNamespace(checkpointNamespace);
        agentExecutionMapper.updateById(update);
    }

    private void publishParentEvent(AgentExecution execution, AgentExecutionEventTypeEnum eventType, String message, Object payload) {
        agentLogService.appendLog(
                execution.getId(),
                execution.getAgentId(),
                execution.getTenantId(),
                resolveParentLogLevel(eventType),
                eventType.getCode(),
                null,
                null,
                buildParentLogContent(message, payload),
                null,
                null
        );
        executionEventStreamService.publish(AgentExecutionEvent.builder()
                .eventType(eventType.getCode())
                .executionId(execution.getId())
                .message(message)
                .payload(payload)
                .timestamp(Instant.now())
                .build());
    }

    private String resolveParentLogLevel(AgentExecutionEventTypeEnum eventType) {
        return switch (eventType) {
            case REQUIRE_INPUT, SECURITY_WARN, PAUSED, FALLBACK_USED -> LOG_LEVEL_WARN;
            case FAILED, BLOCKED, CANCELLED -> LOG_LEVEL_ERROR;
            default -> LOG_LEVEL_INFO;
        };
    }

    private String buildParentLogContent(String message, Object payload) {
        if (payload == null) {
            return message;
        }
        if (!StringUtils.hasText(message)) {
            return String.valueOf(payload);
        }
        return message + System.lineSeparator() + payload;
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String safeText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private boolean shouldUseWebResearchHints(String originalPrompt, AgentTeamMember member, String upstreamEvidence) {
        String combined = String.join("\n",
                safeText(originalPrompt, ""),
                member != null ? safeText(member.getRoleName(), "") : "",
                member != null ? safeText(member.getDescription(), "") : "",
                safeText(upstreamEvidence, "")
        ).toLowerCase(Locale.ROOT);
        return combined.contains("联系方式")
                || combined.contains("contact")
                || combined.contains("联系页")
                || combined.contains("联系人")
                || combined.contains("官网")
                || combined.contains("线索")
                || combined.contains("获客");
    }
}
