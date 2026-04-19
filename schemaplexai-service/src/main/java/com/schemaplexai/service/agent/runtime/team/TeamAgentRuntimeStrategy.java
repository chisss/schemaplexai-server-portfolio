package com.schemaplexai.service.agent.runtime.team;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentModelBindingTypeEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.common.enums.TeamMemberRoleTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberMapper;
import com.schemaplexai.dao.mapper.AgentTeamMemberToolBindingMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentTeamMember;
import com.schemaplexai.model.entity.AgentTeamMemberContextBinding;
import com.schemaplexai.model.entity.AgentTeamMemberToolBinding;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.agent.execution.AgentEngineParams;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.runtime.AgentRuntimeStrategy;
import com.schemaplexai.service.mq.AgentContextPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
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
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern CONCLUSION_BLOCKER_PATTERN = Pattern.compile(
            "(?:当前状态|结论)[\\s\\S]{0,160}?(?:暂不满足|未满足|不满足)",
            Pattern.CASE_INSENSITIVE
    );
    private static final String LOG_LEVEL_INFO = "INFO";
    private static final String LOG_LEVEL_WARN = "WARN";
    private static final String LOG_LEVEL_ERROR = "ERROR";
    private static final List<String> NON_FINAL_SECTION_KEYWORDS = List.of(
            "待补充", "已剔除", "剔除", "风险", "未知", "待执行", "后续动作", "高潜力"
    );

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentTeamMemberToolBindingMapper agentTeamMemberToolBindingMapper;
    private final AgentTeamMemberContextBindingMapper agentTeamMemberContextBindingMapper;
    private final ContextItemMapper contextItemMapper;
    private final AgentExecutionEngine agentExecutionEngine;
    private final AgentLogService agentLogService;
    private final AgentLoopQualityChecker qualityChecker;
    private final com.schemaplexai.service.agent.execution.ExecutionEventStreamService executionEventStreamService;
    private final AgentContextPublisher agentContextPublisher;
    private final DataSource dataSource;

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
    public CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution, AgentExecutionInputDTO input) {
        try {
            AgentExecutionContext context = AgentExecutionContext.builder()
                    .executionId(execution.getId())
                    .agentId(execution.getAgentId())
                    .tenantId(execution.getTenantId())
                    .inputPrompt(execution.getInputPrompt())
                    .inputContext(execution.getInputContext())
                    .model(execution.getAiModel())
                    .conversationId(execution.getConversationId())
                    .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                    .build();
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
        CompileConfig compileConfig = CompileConfig.builder()
                .checkpointSaver(saver)
                .interruptBefore(TeamGraphConstants.NODE_AWAIT_INPUT)
                .releaseThread(false)
                .build();
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
            releaseCheckpoint(compileConfig, runnableConfig);
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
                                        state.qualityGateSummary()))
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
                                                     String retryFeedback) {
        AgentTeamMember leadMember = null;
        List<AgentTeamMember> contributorMembers = new ArrayList<>();
        for (AgentTeamMember member : members) {
            if (leadMember == null && isLeadRole(member.getRoleType())) {
                leadMember = member;
                continue;
            }
            contributorMembers.add(member);
        }
        List<Map<String, Object>> memberResults = new ArrayList<>(executeContributorSequence(
                agent, execution, parentContext, contributorMembers, retryCount, userInput, userOptions, retryFeedback
        ));
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

    private List<Map<String, Object>> executeContributorSequence(Agent agent,
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
            futures.add(executeMember(
                    agent, execution, parentContext, member, retryCount, userInput, userOptions, retryFeedback,
                    upstreamEvidence
            ));
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
        AgentExecution childExecution = new AgentExecution();
        childExecution.setAgentId(agent.getId());
        childExecution.setParentExecutionId(parentExecution.getId());
        childExecution.setTeamMemberId(member.getId());
        childExecution.setRuntimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
        childExecution.setConversationId("team:" + parentExecution.getId() + ":" + member.getId());
        childExecution.setInputPrompt(parentExecution.getInputPrompt());
        childExecution.setInputContext(parentExecution.getInputContext());
        childExecution.setAiModel(modelName);
        childExecution.setStatus(AgentExecutionStatusEnum.QUEUED.getCode());
        agentExecutionMapper.insert(childExecution);

        AgentExecutionContext childContext = AgentExecutionContext.builder()
                .executionId(childExecution.getId())
                .agentId(agent.getId())
                .tenantId(parentExecution.getTenantId())
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
                .stream(false)
                .build();
        if (!StringUtils.hasText(member.getModelOverride())) {
            childContext.setAgentModelType(parentContext.getAgentModelType());
            childContext.setAgentModelGroupId(parentContext.getAgentModelGroupId());
        }

        return agentExecutionEngine.execute(childContext)
                .handle((result, throwable) -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put(TeamGraphConstants.RESULT_MEMBER_ID, member.getId());
                    payload.put(TeamGraphConstants.RESULT_ROLE_NAME, member.getRoleName());
                    payload.put(TeamGraphConstants.RESULT_ROLE_TYPE, member.getRoleType());
                    payload.put(TeamGraphConstants.RESULT_MODEL, modelName);
                    payload.put(TeamGraphConstants.RESULT_EXECUTION_ID, childExecution.getId());
                    if (throwable != null) {
                        payload.put(TeamGraphConstants.RESULT_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
                        payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, throwable.getMessage());
                        return payload;
                    }
                    String status = result != null && StringUtils.hasText(result.getStatus())
                            ? result.getStatus()
                            : AgentExecutionStatusEnum.FAILED.getCode();
                    payload.put(TeamGraphConstants.RESULT_STATUS, status);
                    payload.put(TeamGraphConstants.RESULT_OUTPUT_RESULT, result != null ? result.getOutputResult() : null);
                    payload.put(TeamGraphConstants.RESULT_ERROR_MESSAGE, result != null ? result.getErrorMessage() : null);
                    payload.put(TeamGraphConstants.RESULT_CONVERSATION_ID, result != null ? result.getConversationId() : null);
                    if (StringUtils.hasText((String) payload.get(TeamGraphConstants.RESULT_OUTPUT_RESULT))) {
                        agentContextPublisher.publishAgentOutput(
                                agent.getId(),
                                member.getId(),
                                childExecution.getId(),
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

    Map<String, Object> aggregate(List<Map<String, Object>> memberResults, int retryCount) {
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
            if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status) && StringUtils.hasText(outputResult)) {
                successCount++;
                if (!leadRole) {
                    contributorSuccessCount++;
                }
            }
            if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status) && !StringUtils.hasText(outputResult)) {
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
        update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode());
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
        String finalStatus = StringUtils.hasText(state.finalStatus())
                ? state.finalStatus()
                : AgentExecutionStatusEnum.FAILED.getCode();
        if (AgentExecutionStatusEnum.PAUSED.getCode().equals(finalStatus)) {
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.PAUSED.getCode(),
                    state.finalError(), null, null, null);
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
            agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.COMPLETED.getCode(),
                    null, null, null, state.finalOutput());
            publishParentEvent(execution, AgentExecutionEventTypeEnum.COMPLETED,
                    "Team Agent 执行完成", Map.of(TeamGraphConstants.EVENT_PAYLOAD_FINAL_OUTPUT, state.finalOutput()));
            return AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.COMPLETED.getCode())
                    .outputResult(state.finalOutput())
                    .conversationId(execution.getConversationId())
                    .build();
        }
        agentLogService.updateExecutionStatus(execution.getId(), AgentExecutionStatusEnum.FAILED.getCode(),
                state.finalError(), null, null, null);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.FAILED,
                StringUtils.hasText(state.finalError()) ? state.finalError() : "Team Agent 执行失败", null);
        return AgentExecutionResult.builder()
                .status(AgentExecutionStatusEnum.FAILED.getCode())
                .errorMessage(state.finalError())
                .conversationId(execution.getConversationId())
                .build();
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

    private java.util.Optional<TeamGraphState> resumeGraph(org.bsc.langgraph4j.CompiledGraph<TeamGraphState> compiledGraph,
                                                           RunnableConfig runnableConfig,
                                                           AgentExecution execution,
                                                           AgentExecutionInputDTO resumeInput) throws Exception {
        RunnableConfig updatedConfig = compiledGraph.updateState(
                runnableConfig,
                Map.of(
                        TeamGraphConstants.STATE_USER_INPUT, resumeInput.getMessage(),
                        TeamGraphConstants.STATE_USER_OPTIONS, resumeInput.getOptions() == null ? Map.of() : resumeInput.getOptions()
                )
        );
        publishParentEvent(execution, AgentExecutionEventTypeEnum.USER_INPUT, "收到人工输入，继续执行 Team Graph", null);
        publishParentEvent(execution, AgentExecutionEventTypeEnum.RESUMED, "Team Agent 恢复编排执行", null);
        return compiledGraph.invoke(GraphInput.resume(), updatedConfig);
    }

    private TeamGraphState resolveGraphState(java.util.Optional<TeamGraphState> finalState,
                                             org.bsc.langgraph4j.CompiledGraph<TeamGraphState> compiledGraph,
                                             RunnableConfig runnableConfig,
                                             AgentExecutionContext context) {
        if (finalState.isPresent()) {
            return finalState.get();
        }
        return compiledGraph.lastStateOf(runnableConfig)
                .map(snapshot -> snapshot.state())
                .orElseGet(() -> new TeamGraphState(buildInitialState(context)));
    }

    private void releaseCheckpoint(CompileConfig compileConfig, RunnableConfig runnableConfig) throws Exception {
        if (compileConfig.checkpointSaver().isPresent()) {
            compileConfig.checkpointSaver().get().release(runnableConfig);
        }
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
                .append("- 未知项只能标记为“仓库中未发现”或“当前步骤未生成”，不要模糊化表达。\n");
        promptBuilder.append("工具使用要求:\n")
                .append("- 优先使用 web.fetch 直接抓取官网首页、产品页、联系页，不要依赖 Bash 拼接 curl。\n")
                .append("- 若 web.fetch 返回 links/emails/phones 等结构化字段，优先复用这些字段继续下钻，减少重复猜测 URL。\n")
                .append("- 搜索引擎结果页只能用于发现候选，不能作为最终证据；若官网已可访问，优先使用官网证据。\n");
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
            promptBuilder.append("输出要求:\n")
                    .append("- 输出可直接交付的 Markdown，至少包含“已确认事实”“结论”“关键证据”“风险与未知项”。\n")
                    .append("- 以最终交付文档正文开头，不要输出“Based on the upstream...”“我将开始汇总”之类的过程性说明。\n")
                    .append("- 直接引用系统已确认事实中的模型、流程配置和证据，不要重复写待确认。\n")
                    .append("- 控制输出体量，尽量不超过 ").append(MAX_LEAD_OUTPUT_CHARS).append(" 个字符。\n");
        } else {
            promptBuilder.append("输出要求:\n")
                    .append("- 只输出支撑汇总所需的事实、证据、风险，优先使用短列表。\n")
                    .append("- 不要生成长篇背景描述，控制输出体量，尽量不超过 ")
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

    Integer resolveMinimumCompanyCount(String prompt) {
        if (!StringUtils.hasText(prompt)) {
            return null;
        }
        Matcher matcher = MIN_COMPANY_COUNT_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    int countDetectedCompanyRows(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return 0;
        }
        int tableRowCount = 0;
        for (String rawLine : markdown.split("\\R")) {
            String line = rawLine.trim();
            if (!line.startsWith("|")) {
                continue;
            }
            if (line.matches("^\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?$")) {
                continue;
            }
            List<String> headerCells = parseMarkdownCells(line);
            if (findCompanyHeaderIndex(headerCells) >= 0) {
                continue;
            }
            tableRowCount++;
        }
        return tableRowCount;
    }

    int countQualifiedCompanyRows(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return 0;
        }
        List<String> lines = List.of(markdown.split("\\R"));
        int qualifiedRows = 0;
        int fallbackRows = 0;
        String currentSection = "";
        for (int index = 0; index < lines.size(); ) {
            String line = lines.get(index).trim();
            if (line.startsWith("#")) {
                currentSection = line.replaceFirst("^#+\\s*", "").trim();
                index++;
                continue;
            }
            if (!line.startsWith("|")) {
                index++;
                continue;
            }
            List<String> tableLines = new ArrayList<>();
            while (index < lines.size() && lines.get(index).trim().startsWith("|")) {
                tableLines.add(lines.get(index).trim());
                index++;
            }
            TableCompanyCount tableCount = analyzeCompanyTable(tableLines, currentSection);
            qualifiedRows += tableCount.qualifiedRows();
            fallbackRows += tableCount.fallbackRows();
        }
        return qualifiedRows > 0 ? qualifiedRows : fallbackRows;
    }

    boolean containsConclusionBlocker(String markdown) {
        return StringUtils.hasText(markdown) && CONCLUSION_BLOCKER_PATTERN.matcher(markdown).find();
    }

    private TableCompanyCount analyzeCompanyTable(List<String> tableLines, String sectionTitle) {
        if (CollectionUtils.isEmpty(tableLines)) {
            return TableCompanyCount.empty();
        }
        List<String> headerCells = parseMarkdownCells(tableLines.get(0));
        int companyIndex = findCompanyHeaderIndex(headerCells);
        if (companyIndex < 0) {
            return TableCompanyCount.empty();
        }
        int confidenceIndex = findHeaderIndex(headerCells, "可信度", "confidence");
        int qualifiedRows = 0;
        int fallbackRows = 0;
        for (int index = 1; index < tableLines.size(); index++) {
            String tableLine = tableLines.get(index);
            if (isMarkdownSeparatorLine(tableLine)) {
                continue;
            }
            List<String> cells = parseMarkdownCells(tableLine);
            if (companyIndex >= cells.size()) {
                continue;
            }
            String companyName = cells.get(companyIndex);
            if (!StringUtils.hasText(companyName)) {
                continue;
            }
            if (confidenceIndex >= 0) {
                if (confidenceIndex < cells.size() && isQualifiedConfidence(cells.get(confidenceIndex))) {
                    qualifiedRows++;
                }
                continue;
            }
            if (!isNonFinalSection(sectionTitle)) {
                fallbackRows++;
            }
        }
        return new TableCompanyCount(qualifiedRows, fallbackRows);
    }

    private boolean isMarkdownSeparatorLine(String line) {
        return StringUtils.hasText(line)
                && line.matches("^\\|?(\\s*:?-{3,}:?\\s*\\|)+\\s*:?-{3,}:?\\s*\\|?$");
    }

    private List<String> parseMarkdownCells(String line) {
        if (!StringUtils.hasText(line)) {
            return List.of();
        }
        String normalized = line.trim();
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Arrays.stream(normalized.split("\\|", -1))
                .map(String::trim)
                .toList();
    }

    private int findCompanyHeaderIndex(List<String> headerCells) {
        if (CollectionUtils.isEmpty(headerCells)) {
            return -1;
        }
        for (int index = 0; index < headerCells.size(); index++) {
            if (isCompanyHeaderCell(headerCells.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private int findHeaderIndex(List<String> headerCells, String... keywords) {
        if (CollectionUtils.isEmpty(headerCells) || keywords == null || keywords.length == 0) {
            return -1;
        }
        for (int index = 0; index < headerCells.size(); index++) {
            String header = headerCells.get(index);
            if (!StringUtils.hasText(header)) {
                continue;
            }
            String normalizedHeader = header.toLowerCase(Locale.ROOT);
            for (String keyword : keywords) {
                if (normalizedHeader.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return index;
                }
            }
        }
        return -1;
    }

    private boolean isCompanyHeaderCell(String header) {
        if (!StringUtils.hasText(header)) {
            return false;
        }
        String normalized = header.replace("**", "")
                .replace("`", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        return "公司名称".equals(normalized)
                || "公司".equals(normalized)
                || "企业名称".equals(normalized)
                || "企业".equals(normalized)
                || "客户名称".equals(normalized)
                || "客户".equals(normalized)
                || "目标公司".equals(normalized)
                || "company".equals(normalized)
                || "company name".equals(normalized)
                || "companyname".equals(normalized);
    }

    private boolean isQualifiedConfidence(String confidence) {
        if (!StringUtils.hasText(confidence)) {
            return false;
        }
        String normalized = confidence.toLowerCase(Locale.ROOT);
        return normalized.contains("high") || normalized.contains("medium");
    }

    private boolean isNonFinalSection(String sectionTitle) {
        if (!StringUtils.hasText(sectionTitle)) {
            return false;
        }
        String normalized = sectionTitle.toLowerCase(Locale.ROOT);
        return NON_FINAL_SECTION_KEYWORDS.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private record TableCompanyCount(int qualifiedRows, int fallbackRows) {
        private static TableCompanyCount empty() {
            return new TableCompanyCount(0, 0);
        }
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

    private String resolveParentLogLevel(AgentExecutionEventTypeEnum eventType) {
        return switch (eventType) {
            case REQUIRE_INPUT, SECURITY_WARN, PAUSED -> LOG_LEVEL_WARN;
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
}
