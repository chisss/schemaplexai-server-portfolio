package com.schemaplexai.service.agent.runtime.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentModelBindingTypeEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
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
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.runtime.AgentRuntimeStrategy;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.workflow.ArtifactSceneResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Team Agent 运行时策略
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamAgentRuntimeStrategy implements AgentRuntimeStrategy {

    private static final String JDBC_PREFIX = "jdbc:";
    private static final String JDBC_POSTGRES_PREFIX = "jdbc:postgresql://";
    private static final int DEFAULT_POSTGRES_PORT = 5432;
    private static final String CHECKPOINT_THREAD_REGCLASS = "public.lg4jthread";
    private static final String CHECKPOINT_STATE_REGCLASS = "public.lg4jcheckpoint";
    private static final String CHECKPOINT_TABLE_EXISTS_SQL = """
            select to_regclass(?) is not null as thread_exists,
                   to_regclass(?) is not null as checkpoint_exists
            """;
    private static final String TEAM_WARNING_TITLE = "## Team 执行告警";
    private static final String TEAM_METADATA_TITLE = "## Team 聚合元数据";
    private static final int MAX_MEMBER_RETRY_COUNT = 2;
    private static final int MAX_LEAD_PROMPT_CHARS = 4200;
    private static final int MAX_LEAD_EVIDENCE_CHARS = 2200;
    private static final int MAX_MEMBER_PROMPT_CHARS = 2600;
    private static final int MAX_LEAD_OUTPUT_CHARS = 2600;
    private static final int MAX_MEMBER_OUTPUT_CHARS = 1200;
    private static final int DEFAULT_CHILD_MEMBER_MAX_ROUNDS = 8;
    private static final int DEFAULT_CHILD_LEAD_MAX_ROUNDS = 4;
    private static final int DEFAULT_CHILD_MEMBER_MAX_TOOL_CALLS = 4;
    private static final int DEFAULT_CHILD_LEAD_MAX_TOOL_CALLS = 2;
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
    private static final long MEMBER_EXECUTION_TIMEOUT_MINUTES = 10;

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
    private final DataSource dataSource;
    private final SandboxPolicyResolver sandboxPolicyResolver;
    private final ExecutionSteeringService executionSteeringService;
    private final TeamQualityDetector teamQualityDetector;
    private final LangGraphTeamRuntimeAdapter langGraphRuntimeAdapter = new LangGraphTeamRuntimeAdapter();

    @Value("${spring.datasource.url:}")
    private String datasourceUrl;

    @Value("${spring.datasource.username:}")
    private String datasourceUsername;

    @Value("${spring.datasource.password:}")
    private String datasourcePassword;

    @Override
    public AgentTypeEnum supportType() {
        return AgentTypeEnum.TEAM;
    }

    @Override
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context) {
        try {
            return CompletableFuture.completedFuture(run(agent, execution, context, null));
        } catch (Exception exception) {
            log.error("Team Agent 执行失败: executionId={}", execution.getId(), exception);
            if (teamQualityDetector.isTransientModelChannelFailure(exception.getMessage())) {
                String fallbackOutput = teamQualityDetector.buildExecutionLevelFallbackOutput(execution.getId(), execution.getAgentId(), exception.getMessage());
                agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                        null, null, null, fallbackOutput);
                publishFallbackUsedEvent(execution, "Team Agent 模型通道异常，已生成规则化兜底交付",
                        exception.getMessage(), fallbackOutput);
                return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                        .status(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode())
                        .outputResult(fallbackOutput)
                        .conversationId(execution.getConversationId())
                        .build());
            }
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.FAILED.getCode(),
                    exception.getMessage(), null, null, null);
            publishParentEvent(execution, AgentExecutionEventTypeEnum.FAILED, exception.getMessage(), null);
            return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.FAILED.getCode())
                    .errorMessage(exception.getMessage())
                    .build());
        }
    }

    @Override
    @Async("agentExecutorPool")
    public CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution,
                                                          AgentExecutionContext context, AgentExecutionInputDTO input) {
        try {
            // 消费执行中用户引导：追加到恢复输入，下一成员执行轮自然注入（轮边界消费语义）
            input = mergePendingSteering(execution.getId(), input);
            // 直接使用编排器构建的上下文（含 sandboxPolicy/executionMode），恢复路径不回退默认策略
            return CompletableFuture.completedFuture(run(agent, execution, context, input));
        } catch (Exception exception) {
            log.error("恢复 Team Agent 执行失败: executionId={}", execution.getId(), exception);
            return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.FAILED.getCode())
                    .errorMessage(exception.getMessage())
                    .build());
        }
    }

    private AgentExecutionResult run(Agent agent, AgentExecution execution, AgentExecutionContext context, AgentExecutionInputDTO resumeInput) throws Exception {
        List<AgentTeamMember> members = loadMembers(agent.getId());
        if (members.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "Team Agent 未配置成员");
        }
        validateRuntimeConfiguration(members);

        String threadId = StringUtils.hasText(execution.getGraphThreadId())
                ? execution.getGraphThreadId()
                : "team-" + execution.getId();
        String checkpointNamespace = "team-agent-" + agent.getId();
        persistGraphMetadata(execution.getId(), threadId, checkpointNamespace);
        execution.setGraphThreadId(threadId);
        execution.setCheckpointNamespace(checkpointNamespace);
        agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.RUNNING.getCode(), null, null, null, null);
        if (resumeInput == null) {
            publishParentEvent(execution, AgentExecutionEventTypeEnum.ROUND_START, "Team Agent 开始编排成员执行", null);
        }

        StateGraph<TeamGraphState> graphDefinition = buildGraph(agent, execution, context, members);
        PostgresSaver saver = buildSaver(graphDefinition);
        CompileConfig compileConfig = langGraphRuntimeAdapter.buildCompileConfig(saver);
        var compiledGraph = graphDefinition.compile(compileConfig);
        var runnableConfig = RunnableConfig.builder()
                .threadId(threadId)
                .build();

        var finalState = resumeInput == null
                ? compiledGraph.invoke(buildInitialState(context), runnableConfig)
                : resumeGraph(compiledGraph, runnableConfig, execution, resumeInput);
        TeamGraphState state = resolveGraphState(finalState, compiledGraph, runnableConfig, context);
        AgentExecutionResult result = finalizeExecution(execution, state);
        if (!AgentExecutionStatusEnum.PAUSED.getCode().equals(result.getStatus())) {
            langGraphRuntimeAdapter.releaseCheckpoint(compileConfig, runnableConfig);
        }
        return result;
    }

    private StateGraph<TeamGraphState> buildGraph(Agent agent, AgentExecution execution, AgentExecutionContext context,
                                                  List<AgentTeamMember> members) throws Exception {
        var memberCycleSubgraph = buildMemberCycleSubgraph(agent, execution, context, members).compile();
        return new StateGraph<>(TeamGraphState::new)
                .addNode(TeamGraphConstants.NODE_PLAN, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_PLAN_SUMMARY, buildPlanSummary(agent, members))
                ))
                .addNode(TeamGraphConstants.NODE_MEMBER_CYCLE, memberCycleSubgraph)
                .addNode(TeamGraphConstants.NODE_AWAIT_INPUT, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.RUNNING.getCode())
                ))
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

    private StateGraph<TeamGraphState> buildMemberCycleSubgraph(Agent agent, AgentExecution execution,
                                                                AgentExecutionContext context,
                                                                List<AgentTeamMember> members) throws Exception {
        return new StateGraph<>(TeamGraphState::new)
                .addNode(TeamGraphConstants.NODE_EXECUTE_MEMBERS, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_MEMBER_RESULTS,
                                executeMembers(agent, execution, context, members,
                                        state.retryCount(), state.userInput(), state.userOptions(),
                                        state.qualityGateSummary(), state.memberResults()))
                ))
                .addNode(TeamGraphConstants.NODE_AGGREGATE, node_async(state ->
                        aggregate(state.memberResults(), state.retryCount())
                ))
                .addNode(TeamGraphConstants.NODE_QUALITY_GATE, node_async(state ->
                        evaluateAggregateQuality(execution, context, state)
                ))
                .addNode(TeamGraphConstants.NODE_RETRY, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_RETRY_COUNT, state.retryCount() + 1)
                ))
                .addEdge(START, TeamGraphConstants.NODE_EXECUTE_MEMBERS)
                .addEdge(TeamGraphConstants.NODE_EXECUTE_MEMBERS, TeamGraphConstants.NODE_AGGREGATE)
                .addEdge(TeamGraphConstants.NODE_AGGREGATE, TeamGraphConstants.NODE_QUALITY_GATE)
                .addConditionalEdges(TeamGraphConstants.NODE_QUALITY_GATE,
                        edge_async(state -> TeamGraphConstants.QUALITY_GATE_RETRY.equals(state.qualityGateDecision())
                                && state.retryCount() < MAX_MEMBER_RETRY_COUNT
                                ? TeamGraphConstants.NODE_RETRY
                                : END),
                        Map.of(
                                TeamGraphConstants.NODE_RETRY, TeamGraphConstants.NODE_RETRY,
                                END, END
                        ))
                .addEdge(TeamGraphConstants.NODE_RETRY, TeamGraphConstants.NODE_EXECUTE_MEMBERS);
    }

    private List<Map<String, Object>> executeMembers(Agent agent, AgentExecution execution, AgentExecutionContext parentContext,
                                                     List<AgentTeamMember> members, int retryCount,
                                                     String userInput, Map<String, Object> userOptions,
                                                     String retryFeedback, List<Map<String, Object>> previousResults) {
        // 父执行已停止（如工作流编排超时写入 stopped）：跳过本轮成员执行，防后续轮次继续产生模型/工具副作用
        if (isParentExecutionStopped(execution.getId())) {
            log.info("父执行已被停止，跳过本轮成员执行: executionId={}", execution.getId());
            return List.of(Map.of(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.STOPPED.getCode()));
        }
        // 成功成员复用（审计 P0-3）：恢复/质量重试时不重复执行已完成成员，防写工具重复副作用
        Set<String> completedMemberIds = resolveCompletedMemberIds(previousResults);
        List<Map<String, Object>> memberResults = new ArrayList<>();
        if (!completedMemberIds.isEmpty()) {
            memberResults.addAll(previousResults.stream()
                    .filter(result -> completedMemberIds.contains(asText(result.get(TeamGraphConstants.RESULT_MEMBER_ID))))
                    .toList());
        }

        AgentTeamMember leadMember = null;
        List<AgentTeamMember> contributorMembers = new ArrayList<>();
        for (AgentTeamMember member : members) {
            if (completedMemberIds.contains(member.getId())) {
                log.info("复用已完成成员结果，跳过执行: memberId={}, executionId={}", member.getId(), execution.getId());
                continue;
            }
            if (leadMember == null && isLeadRole(member.getRoleType())) {
                leadMember = member;
                continue;
            }
            contributorMembers.add(member);
        }

        if (leadMember == null && contributorMembers.isEmpty()) {
            return memberResults;
        }

        TeamMemberExecutionMode mode = resolveExecutionMode(contributorMembers, execution.getTenantId(), agent.getId());
        log.info("Team 成员执行模式: mode={}, contributors={}, executionId={}",
                mode.getCode(), contributorMembers.size(), execution.getId());

        if (mode == TeamMemberExecutionMode.PARALLEL && contributorMembers.size() > 1) {
            memberResults.addAll(executeMemberBatch(
                    agent, execution, parentContext, contributorMembers, retryCount, userInput, userOptions, retryFeedback, null
            ));
        } else {
            memberResults.addAll(executeContributorsPipeline(
                    agent, execution, parentContext, contributorMembers, retryCount, userInput, userOptions, retryFeedback
            ));
        }

        if (leadMember == null || hasPausedMemberResult(memberResults)) {
            return memberResults;
        }
        String leadEvidence = buildMemberEvidencePrompt(memberResults);
        memberResults.add(executeMember(
                agent, execution, parentContext, leadMember, retryCount, userInput, userOptions, retryFeedback,
                leadEvidence
        ).join());
        return memberResults;
    }

    /**
     * Team 成员默认流水线执行，降低外部模型并发 EOF 风险并保留成员间证据传递。
     */
    private TeamMemberExecutionMode resolveExecutionMode(List<AgentTeamMember> contributors, String tenantId, String agentId) {
        return TeamMemberExecutionMode.PIPELINE;
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
     * 合并执行中用户引导到恢复输入：pending → applied CAS，追加为最高优先级引导段
     */
    private AgentExecutionInputDTO mergePendingSteering(String executionId, AgentExecutionInputDTO input) {
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
        AgentExecutionInputDTO merged = input != null ? input : new AgentExecutionInputDTO();
        String existing = StringUtils.hasText(merged.getMessage()) ? merged.getMessage() : "";
        merged.setMessage((StringUtils.hasText(existing) ? existing + "\n\n" : "")
                + ExecutionSteeringService.buildSteeringWrapper(guidance.toString()));
        return merged;
    }

    /**
     * 查找可恢复的子执行（PAUSED 且属于当前父执行+成员），恢复时复用其 ID 继续
     */
    private AgentExecution findResumableChildExecution(String parentExecutionId, String teamMemberId) {
        return agentExecutionMapper.selectOne(new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getParentExecutionId, parentExecutionId)
                .eq(AgentExecution::getTeamMemberId, teamMemberId)
                .eq(AgentExecution::getStatus, AgentExecutionStatusEnum.PAUSED.getCode())
                .orderByDesc(AgentExecution::getCreatedAt)
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
                                                     AgentExecution childExecution) {
        String modelName = StringUtils.hasText(member.getModelOverride()) ? member.getModelOverride() : parentExecution.getAiModel();
        AgentExecutionContext childContext = AgentExecutionContext.builder()
                .executionId(childExecution.getId())
                .agentId(agent.getId())
                .tenantId(parentExecution.getTenantId())
                .userId(parentContext.getUserId())
                .parentExecutionId(parentExecution.getId())
                .teamAgentId(agent.getId())
                .teamMemberId(member.getId())
                .teamMemberRoleName(member.getRoleName())
                .teamMemberRoleType(member.getRoleType())
                .inputPrompt(buildMemberPrompt(
                        parentContext.getInputPrompt(), member, retryCount, userInput, userOptions, retryFeedback,
                        upstreamEvidence
                ))
                .inputContext(parentContext.getInputContext())
                .model(modelName)
                .agentModelType(AgentModelBindingTypeEnum.MODEL.getCode())
                .conversationId(childExecution.getConversationId())
                .maxRounds(resolveChildMaxRounds(parentContext, member))
                .maxToolCallsPerRound(resolveChildMaxToolCalls(parentContext, member))
                .maxMessages(resolveChildMaxMessages(parentContext, member))
                .overrideToolBindings(loadMemberToolBindings(parentExecution.getTenantId(), agent.getId(), member.getId()))
                .additionalSystemContexts(loadMemberSystemContexts(member))
                .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                .sandboxPolicy(resolveChildSandboxPolicy(agent, parentContext, member))
                .executionMode(StringUtils.hasText(parentContext.getExecutionMode())
                        ? parentContext.getExecutionMode()
                        : "auto")
                .stream(false)
                .temporaryChat(parentContext.isTemporaryChat())
                .memoryWriteEnabled(parentContext.getMemoryWriteEnabled())
                .build();
        if (!StringUtils.hasText(member.getModelOverride())) {
            childContext.setAgentModelType(parentContext.getAgentModelType());
            childContext.setAgentModelGroupId(parentContext.getAgentModelGroupId());
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

    private boolean memberHasWriteTools(String tenantId, String agentId, String memberId) {
        List<AgentTeamMemberToolBinding> bindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .eq(AgentTeamMemberToolBinding::getMemberId, memberId)
                        .eq(AgentTeamMemberToolBinding::getEnabled, true));
        if (CollectionUtils.isEmpty(bindings)) {
            return false;
        }
        List<String> toolCodes = bindings.stream().map(AgentTeamMemberToolBinding::getToolCode).toList();
        List<BuiltinTool> tools = builtinToolMapper.selectList(
                new LambdaQueryWrapper<BuiltinTool>()
                        .in(BuiltinTool::getCode, toolCodes)
                        .eq(BuiltinTool::getEnabled, true));
        return tools.stream().anyMatch(t -> ToolIoTypeEnum.fromCode(t.getIoType()).isWrite());
    }

    private List<Map<String, Object>> executeContributorsPipeline(Agent agent,
                                                                   AgentExecution execution,
                                                                   AgentExecutionContext parentContext,
                                                                   List<AgentTeamMember> members,
                                                                   int retryCount,
                                                                   String userInput,
                                                                   Map<String, Object> userOptions,
                                                                   String retryFeedback) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (CollectionUtils.isEmpty(members)) {
            return results;
        }
        for (AgentTeamMember member : members) {
            String upstreamEvidence = buildMemberEvidencePrompt(results);
            Map<String, Object> memberResult = executeMember(
                    agent, execution, parentContext, member, retryCount, userInput, userOptions, retryFeedback,
                    upstreamEvidence
            ).join();
            results.add(memberResult);
            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)))) {
                break;
            }
        }
        return results;
    }

    private List<Map<String, Object>> executeMemberBatch(Agent agent, AgentExecution execution,
                                                         AgentExecutionContext parentContext,
                                                         List<AgentTeamMember> members,
                                                         int retryCount,
                                                         String userInput,
                                                         Map<String, Object> userOptions,
                                                         String retryFeedback,
                                                         String upstreamEvidence) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (AgentTeamMember member : members) {
            CompletableFuture<Map<String, Object>> future = executeMember(
                    agent, execution, parentContext, member, retryCount, userInput, userOptions, retryFeedback,
                    upstreamEvidence
            ).orTimeout(MEMBER_EXECUTION_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                    .exceptionally(throwable -> {
                        log.error("Team 成员并行执行异常: memberId={}, error={}", member.getId(), throwable.getMessage());
                        Map<String, Object> errorPayload = new LinkedHashMap<>();
                        errorPayload.put(TeamGraphConstants.RESULT_MEMBER_ID, member.getId());
                        errorPayload.put(TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName());
                        errorPayload.put(TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType());
                        errorPayload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                        String errorMsg = throwable instanceof TimeoutException
                                ? "成员执行超时（" + MEMBER_EXECUTION_TIMEOUT_MINUTES + "分钟）"
                                : throwable.getMessage();
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
                                                                 String upstreamEvidence) {
        String modelName = StringUtils.hasText(member.getModelOverride()) ? member.getModelOverride() : parentExecution.getAiModel();
        // 恢复语义（裁决 #3）：优先复用既有 PAUSED 子执行，恢复原 childExecutionId 继续，不新建
        AgentExecution childExecution = findResumableChildExecution(parentExecution.getId(), member.getId());
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
            childExecution.setConversationId("team:" + parentExecution.getId() + ":" + member.getId()
                    + (retryCount > 0 ? ":r" + retryCount : ""));
            childExecution.setInputPrompt(parentExecution.getInputPrompt());
            childExecution.setInputContext(parentExecution.getInputContext());
            childExecution.setAiModel(modelName);
            childExecution.setStatus(AgentExecutionStatusEnum.QUEUED.getCode());
            agentExecutionMapper.insert(childExecution);
        }

        AgentExecutionContext childContext = buildChildExecutionContext(agent, parentExecution, parentContext, member,
                retryCount, userInput, userOptions, retryFeedback, upstreamEvidence, childExecution);

        AgentExecutionInputDTO resumeInput = new AgentExecutionInputDTO();
        resumeInput.setMessage(userInput);
        // lambda 内引用需 effectively final：捕获本次执行实际使用的子执行
        AgentExecution capturedChild = childExecution;
        return (resuming ? agentExecutionEngine.resume(childContext, resumeInput) : agentExecutionEngine.execute(childContext))
                .handle((result, throwable) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(TeamGraphConstants.RESULT_MEMBER_ID, member.getId());
                    payload.put(TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName());
                    payload.put(TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType());
                    payload.put(TeamGraphConstants.RESULT_MODEL, modelName);
                    payload.put(TeamGraphConstants.RESULT_EXECUTION_ID, capturedChild.getId());
                    if (throwable != null) {
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
                    payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT, result != null ? result.getOutputResult() : null);
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
        payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
        payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT, fallbackOutput);
        payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE,
                "模型通道异常，已生成成员级规则化兜底结果: " + safeText(errorMessage, ""));
        payload.put(TeamGraphConstants.RESULT_CONVERSATION_ID, childExecution.getConversationId());
        agentLogService.updateExecutionStatus(childExecution.getId(), AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                null, null, null, fallbackOutput);
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
        for (Map<String, Object> memberResult : memberResults) {
            String roleName = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME));
            String roleType = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_TYPE));
            String status = asText(memberResult.get(TeamGraphConstants.RESULT_STATUS));
            String outputResult = asText(memberResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT));
            boolean leadRole = isLeadRole(roleType);
            appendDelimitedSummary(modelSummaries, roleName, asText(memberResult.get(TeamGraphConstants.RESULT_MODEL)));
            appendDelimitedSummary(executionSummaries, roleName, asText(memberResult.get(TeamGraphConstants.RESULT_EXECUTION_ID)));
            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(status)) {
                pausedCount++;
            }
            if (teamQualityDetector.isSuccessfulOutputStatus(status) && StringUtils.hasText(outputResult)) {
                successCount++;
                if (!leadRole) {
                    contributorSuccessCount++;
                }
            }
            if (AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode().equals(status)) {
                fallbackUsed = true;
            }
            if (teamQualityDetector.isSuccessfulOutputStatus(status) && !StringUtils.hasText(outputResult)) {
                warnings.add("[" + roleName + "] 执行已完成但未产生可用输出");
            }
            if (!leadRole) {
                hasContributor = true;
            }
            if (StringUtils.hasText(outputResult)) {
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
                        buildAggregateOverview(memberResults.size(), 0, retryCount, modelSummaries, executionSummaries));
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
        update.put(TeamGraphConstants.STATE_FINAL_STATUS, fallbackUsed
                ? AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode()
                : AgentExecutionStatusEnum.COMPLETED.getCode());
        update.put(TeamGraphConstants.STATE_FINAL_OUTPUT, leadOutput);
        update.put(TeamGraphConstants.STATE_AGGREGATE_META,
                buildAggregateOverview(memberResults.size(), successCount, retryCount, modelSummaries, executionSummaries));
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

    private Map<String, Object> evaluateAggregateQuality(AgentExecution execution,
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
        if (!StringUtils.hasText(state.finalOutput())) {
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_FAIL,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, "Team 聚合输出为空"
            );
        }

        Integer minimumCompanyCount = resolveMinimumCompanyCount(context != null ? context.getInputPrompt() : null);
        if (minimumCompanyCount != null) {
            int detectedCompanyCount = countQualifiedCompanyRows(state.finalOutput());
            if (detectedCompanyCount < minimumCompanyCount) {
                String message = "最终主表仅检测到 " + detectedCompanyCount + " 家 high/medium 公司，低于要求的 "
                        + minimumCompanyCount + " 家，请继续补充公开线索后再交付";
                if (state.retryCount() < MAX_MEMBER_RETRY_COUNT) {
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
            if (containsConclusionBlocker(state.finalOutput())) {
                String message = "最终结论仍明确标记为未满足交付条件，请继续补充公开线索后再交付";
                if (state.retryCount() < MAX_MEMBER_RETRY_COUNT) {
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
                context, state.finalOutput(), null, 0, buildTeamQualityParams()
        );
        if (feedback == null) {
            publishTeamQualityGateEvent(execution, TeamGraphConstants.QUALITY_GATE_PASS, "Team 聚合输出通过质量闸门");
            return Map.of(
                    TeamGraphConstants.STATE_QUALITY_GATE_DECISION, TeamGraphConstants.QUALITY_GATE_PASS,
                    TeamGraphConstants.STATE_QUALITY_GATE_SUMMARY, "Team 聚合输出通过质量闸门"
            );
        }
        if (state.retryCount() < MAX_MEMBER_RETRY_COUNT) {
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

    private AgentExecutionResult finalizeExecution(AgentExecution execution, TeamGraphState state) {
        // 父执行已被停止（如工作流编排超时写入 stopped）：保持 stopped 终态，不覆盖为 COMPLETED
        if (isParentExecutionStopped(execution.getId())) {
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.STOPPED.getCode(),
                    "用户已停止执行", null, null, null);
            publishParentEvent(execution, AgentExecutionEventTypeEnum.CANCELLED, "Team Agent 已停止", null);
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.STOPPED.getCode())
                    .errorMessage("用户已停止执行")
                    .conversationId(execution.getConversationId())
                    .build();
        }
        String finalStatus = StringUtils.hasText(state.finalStatus())
                ? state.finalStatus()
                : AgentExecutionStatusEnum.FAILED.getCode();
        TokenUsageSummary tokenUsage = summarizeTeamTokenUsage(execution.getId());
        if (AgentExecutionStatusEnum.PAUSED.getCode().equals(finalStatus)) {
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.PAUSED.getCode(),
                    state.finalError(), tokenUsage.inputTokens(), tokenUsage.outputTokens(), null);
            Map<String, Object> pausedPayload = new LinkedHashMap<>();
            pausedPayload.put(TeamGraphConstants.EVENT_PAYLOAD_GRAPH_THREAD_ID, execution.getGraphThreadId());
            pausedPayload.put(TeamGraphConstants.EVENT_PAYLOAD_CHECKPOINT_NAMESPACE, execution.getCheckpointNamespace());
            publishParentEvent(execution, AgentExecutionEventTypeEnum.REQUIRE_INPUT,
                    "Team Agent 等待人工输入", pausedPayload);
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.PAUSED.getCode())
                    .errorMessage(state.finalError())
                    .conversationId(execution.getConversationId())
                    .build();
        }
        if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(finalStatus)) {
            discardPendingSteeringOnTerminal(execution.getId());
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.COMPLETED.getCode(),
                    null, tokenUsage.inputTokens(), tokenUsage.outputTokens(), state.finalOutput());
            publishParentEvent(execution, AgentExecutionEventTypeEnum.COMPLETED,
                    "Team Agent 执行完成", Map.of(TeamGraphConstants.EVENT_PAYLOAD_FINAL_OUTPUT, state.finalOutput()));
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.COMPLETED.getCode())
                    .outputResult(state.finalOutput())
                    .conversationId(execution.getConversationId())
                    .build();
        }
        if (AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode().equals(finalStatus)) {
            discardPendingSteeringOnTerminal(execution.getId());
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                    null, tokenUsage.inputTokens(), tokenUsage.outputTokens(), state.finalOutput());
            publishFallbackUsedEvent(execution, "Team Agent 已启用规则化兜底交付",
                    state.finalError(), state.finalOutput());
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode())
                    .outputResult(state.finalOutput())
                    .conversationId(execution.getConversationId())
                    .build();
        }
        if (teamQualityDetector.isTransientModelChannelFailure(state.finalError())) {
            discardPendingSteeringOnTerminal(execution.getId());
            String fallbackOutput = teamQualityDetector.buildExecutionLevelFallbackOutput(execution.getId(), execution.getAgentId(), state.finalError());
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode(),
                    null, tokenUsage.inputTokens(), tokenUsage.outputTokens(), fallbackOutput);
            publishFallbackUsedEvent(execution, "Team Agent 模型通道异常，已生成规则化兜底交付",
                    state.finalError(), fallbackOutput);
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode())
                    .outputResult(fallbackOutput)
                    .conversationId(execution.getConversationId())
                    .build();
        }
        discardPendingSteeringOnTerminal(execution.getId());
        agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.FAILED.getCode(),
                state.finalError(), tokenUsage.inputTokens(), tokenUsage.outputTokens(), null);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.FAILED,
                StringUtils.hasText(state.finalError()) ? state.finalError() : "Team Agent 执行失败", null);
        return AgentExecutionResult.builder()
                .status(AgentExecutionStatusEnum.FAILED.getCode())
                .errorMessage(state.finalError())
                .conversationId(execution.getConversationId())
                .build();
    }

    /**
     * 父执行是否已被停止（复用 Solo 引擎的 stopped 状态语义：
     * 工作流编排超时/用户停止均写入 stopped 状态）。
     */
    private boolean isParentExecutionStopped(String parentExecutionId) {
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



    private synchronized PostgresSaver buildSaver(StateGraph<TeamGraphState> graphDefinition) throws Exception {
        if (!StringUtils.hasText(datasourceUrl) || !datasourceUrl.startsWith(JDBC_POSTGRES_PREFIX)) {
            throw new IllegalStateException("Team Agent 仅支持 PostgreSQL checkpoint，当前数据源配置无效");
        }
        URI uri = URI.create(datasourceUrl.substring(JDBC_PREFIX.length()));
        String host = uri.getHost();
        String database = StringUtils.trimLeadingCharacter(uri.getPath(), '/');
        if (!StringUtils.hasText(host) || !StringUtils.hasText(database)) {
            throw new IllegalStateException("Team Agent checkpoint 数据源缺少 host 或 database 配置");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_POSTGRES_PORT;
        boolean createTables = !checkpointTablesExist();
        return PostgresSaver.builder()
                .host(host)
                .port(port)
                .database(database)
                .user(datasourceUsername)
                .password(datasourcePassword)
                .stateSerializer(graphDefinition.getStateSerializer())
                .createTables(createTables)
                .build();
    }

    private boolean checkpointTablesExist() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(CHECKPOINT_TABLE_EXISTS_SQL)) {
            statement.setString(1, CHECKPOINT_THREAD_REGCLASS);
            statement.setString(2, CHECKPOINT_STATE_REGCLASS);
            try (var resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getBoolean("thread_exists") && resultSet.getBoolean("checkpoint_exists");
                }
            }
        }
        return false;
    }

    private Optional<TeamGraphState> resumeGraph(CompiledGraph<TeamGraphState> compiledGraph,
                                                 RunnableConfig runnableConfig,
                                                 AgentExecution execution,
                                                 AgentExecutionInputDTO resumeInput) throws Exception {
        publishParentEvent(execution, AgentExecutionEventTypeEnum.USER_INPUT, "收到人工输入，继续执行 Team Graph", null);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.RESUMED, "Team Agent 恢复编排执行", null);
        return langGraphRuntimeAdapter.resume(compiledGraph, runnableConfig, resumeInput.getMessage(), resumeInput.getOptions());
    }

    private TeamGraphState resolveGraphState(Optional<TeamGraphState> finalState,
                                             CompiledGraph<TeamGraphState> compiledGraph,
                                             RunnableConfig runnableConfig,
                                             AgentExecutionContext context) {
        if (finalState.isPresent()) {
            return finalState.get();
        }
        return langGraphRuntimeAdapter.lastStateOf(compiledGraph, runnableConfig)
                .orElseGet(() -> new TeamGraphState(buildInitialState(context)));
    }

    private Map<String, Object> buildInitialState(AgentExecutionContext context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put(TeamGraphConstants.STATE_RETRY_COUNT, 0);
        state.put(TeamGraphConstants.STATE_USER_INPUT, "");
        state.put(TeamGraphConstants.STATE_USER_OPTIONS, Map.of());
        state.put(TeamGraphConstants.STATE_INPUT_PROMPT, context.getInputPrompt());
        return state;
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
            throw new BusinessException(ResultCode.BAD_REQUEST, validationError);
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
        StringBuilder promptBuilder = new StringBuilder();
        boolean leadRole = TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.getRoleType());
        boolean customerDeliveryContext = ArtifactSceneResolver.isCustomerDeliveryPrompt(originalPrompt);
        boolean contactResearchContext = shouldUseWebResearchHints(originalPrompt, member, upstreamEvidence);
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
                .append("- 如果系统已确认事实块已经给出明确值，禁止继续写成“待确认”“可能”“推测”。\n");
        if (customerDeliveryContext) {
            promptBuilder.append("- 客户交付场景中，未知项统一放入“风险与边界”或“待确认事项”，不要写内部缺失占位语或回填提示。\n");
        } else {
            promptBuilder.append("- 未知项只能标记为“仓库中未发现”或“当前步骤未生成”，不要模糊化表达。\n");
        }
        promptBuilder.append("工具使用要求:\n");
        if (contactResearchContext) {
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
            if (leadRole) {
                promptBuilder.append("引用规则:\n")
                        .append("- 最终交付必须以“上游成员证据”为准进行汇总。\n")
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
            promptBuilder.append("输出要求:\n");
            if (customerDeliveryContext) {
                promptBuilder.append("- 输出面向客户或业务负责人的最终交付 Markdown，至少包含“已确认事实”“交付方案”“交付清单/执行步骤”“预期业务价值”“风险与边界”。\n")
                        .append("- 不要输出仓库审计术语、工具过程、回填说明或其他内部表达。\n");
            } else {
                promptBuilder.append("- 输出可直接交付的 Markdown，至少包含“已确认事实”“结论”“关键证据”“风险与未知项”。\n");
            }
            promptBuilder.append("- 以最终交付文档正文开头，不要输出“Based on the upstream...”“我将开始汇总”之类的过程性说明。\n")
                    .append("- 直接引用系统已确认事实中的模型、流程配置和证据，不要重复写待确认。\n")
                    .append("- 控制输出体量，尽量不超过 ").append(MAX_LEAD_OUTPUT_CHARS).append(" 个字符。\n");
        } else {
            promptBuilder.append("输出要求:\n");
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
                .append(compressStructuredText(originalPrompt, leadRole ? MAX_LEAD_PROMPT_CHARS : MAX_MEMBER_PROMPT_CHARS));
        return promptBuilder.toString();
    }

    private boolean hasPausedMemberResult(List<Map<String, Object>> memberResults) {
        if (CollectionUtils.isEmpty(memberResults)) {
            return false;
        }
        return memberResults.stream()
                .map(memberResult -> asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)))
                .anyMatch(AgentExecutionStatusEnum.PAUSED.getCode()::equals);
    }

    private String buildMemberEvidencePrompt(List<Map<String, Object>> memberResults) {
        if (CollectionUtils.isEmpty(memberResults)) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## 上游成员已确认事实与证据\n");
        for (Map<String, Object> memberResult : memberResults) {
            String roleType = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_TYPE));
            if (isLeadRole(roleType)) {
                continue;
            }
            String roleName = safeText(asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME)), "未命名成员");
            String status = safeText(asText(memberResult.get(TeamGraphConstants.RESULT_STATUS)), "unknown");
            String executionId = asText(memberResult.get(TeamGraphConstants.RESULT_EXECUTION_ID));
            String outputResult = asText(memberResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT));
            String errorMessage = asText(memberResult.get(TeamGraphConstants.RESULT_ERROR_MESSAGE));
            builder.append("### ").append(roleName).append("\n")
                    .append("- 执行状态: ").append(status).append("\n");
            if (StringUtils.hasText(executionId)) {
                builder.append("- 执行ID: ").append(executionId).append("\n");
            }
            if (StringUtils.hasText(outputResult)) {
                builder.append(compressStructuredText(outputResult, MAX_MEMBER_OUTPUT_CHARS)).append("\n");
            }
            if (StringUtils.hasText(errorMessage)) {
                builder.append("- 错误: ").append(errorMessage).append("\n");
            }
            builder.append("\n");
        }
        return compressStructuredText(builder.toString().trim(), MAX_LEAD_EVIDENCE_CHARS);
    }

    private int resolveChildMaxRounds(AgentExecutionContext parentContext, AgentTeamMember member) {
        boolean leadRole = isLeadRole(member.getRoleType());
        int parentMaxRounds = parentContext != null && parentContext.getMaxRounds() > 0
                ? parentContext.getMaxRounds() : 0;
        int target = leadRole ? DEFAULT_CHILD_LEAD_MAX_ROUNDS : DEFAULT_CHILD_MEMBER_MAX_ROUNDS;
        int minimum = leadRole ? MIN_CHILD_LEAD_MAX_ROUNDS : MIN_CHILD_MEMBER_MAX_ROUNDS;
        if (parentMaxRounds > 0) {
            target = Math.min(parentMaxRounds, target);
        }
        return Math.max(target, minimum);
    }

    private int resolveChildMaxToolCalls(AgentExecutionContext parentContext, AgentTeamMember member) {
        boolean leadRole = isLeadRole(member.getRoleType());
        int parentMaxToolCalls = parentContext != null && parentContext.getMaxToolCallsPerRound() > 0
                ? parentContext.getMaxToolCallsPerRound() : 0;
        int target = leadRole ? DEFAULT_CHILD_LEAD_MAX_TOOL_CALLS : DEFAULT_CHILD_MEMBER_MAX_TOOL_CALLS;
        if (parentMaxToolCalls > 0) {
            target = Math.min(parentMaxToolCalls, target);
        }
        return Math.max(target, 1);
    }

    private int resolveChildMaxMessages(AgentExecutionContext parentContext, AgentTeamMember member) {
        int maxRounds = resolveChildMaxRounds(parentContext, member);
        int maxToolCalls = resolveChildMaxToolCalls(parentContext, member);
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

    private List<AgentToolBinding> loadMemberToolBindings(String tenantId, String agentId, String memberId) {
        List<AgentTeamMemberToolBinding> memberBindings = agentTeamMemberToolBindingMapper.selectList(
                new LambdaQueryWrapper<AgentTeamMemberToolBinding>()
                        .eq(AgentTeamMemberToolBinding::getMemberId, memberId)
                        .eq(AgentTeamMemberToolBinding::getEnabled, true)
                        .orderByAsc(AgentTeamMemberToolBinding::getPriority));
        if (CollectionUtils.isEmpty(memberBindings)) {
            return null;
        }
        return memberBindings.stream().map(binding -> {
            AgentToolBinding agentToolBinding = new AgentToolBinding();
            agentToolBinding.setId(binding.getId());
            agentToolBinding.setTenantId(tenantId);
            agentToolBinding.setAgentId(agentId);
            agentToolBinding.setToolCode(binding.getToolCode());
            agentToolBinding.setSourceType(binding.getSourceType());
            agentToolBinding.setSourceRefId(binding.getSourceRefId());
            agentToolBinding.setEnabled(binding.getEnabled());
            agentToolBinding.setPriority(binding.getPriority());
            agentToolBinding.setConfigOverride(binding.getConfigOverride());
            return agentToolBinding;
        }).toList();
    }

    private List<String> loadMemberSystemContexts(AgentTeamMember member) {
        List<String> contexts = new ArrayList<>();
        contexts.add("## Team 成员角色说明\n请仅承担当前角色职责，并输出便于团队汇总的阶段性结果。");
        if (TeamMemberRoleTypeEnum.LEAD_AGENT.matches(member.getRoleType())) {
            contexts.add("## Lead Agent 约束\n你负责拆解任务、协调整体结果，不直接展开所有细节实现。");
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

    private void publishFallbackUsedEvent(AgentExecution execution, String message, String reason, String fallbackOutput) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(TeamGraphConstants.EVENT_PAYLOAD_FINAL_OUTPUT, fallbackOutput);
        payload.put("fallbackReason", safeText(reason, "模型通道异常"));
        payload.put("reviewRequired", true);
        payload.put("recommendedActions", List.of("重新运行", "转人工复核", "查看 trace"));
        publishParentEvent(execution, AgentExecutionEventTypeEnum.FALLBACK_USED, message, payload);
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
