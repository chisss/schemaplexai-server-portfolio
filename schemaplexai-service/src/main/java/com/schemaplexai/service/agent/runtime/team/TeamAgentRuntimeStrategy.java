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
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
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
import org.postgresql.Driver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
    private static final int MAX_MEMBER_RETRY_COUNT = 1;
    private static final String LOG_LEVEL_INFO = "INFO";
    private static final String LOG_LEVEL_WARN = "WARN";
    private static final String LOG_LEVEL_ERROR = "ERROR";

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentTeamMemberMapper agentTeamMemberMapper;
    private final AgentTeamMemberToolBindingMapper agentTeamMemberToolBindingMapper;
    private final AgentTeamMemberContextBindingMapper agentTeamMemberContextBindingMapper;
    private final ContextItemMapper contextItemMapper;
    private final AgentExecutionEngine agentExecutionEngine;
    private final AgentLogService agentLogService;
    private final com.schemaplexai.service.agent.execution.ExecutionEventStreamService executionEventStreamService;
    private final AgentContextPublisher agentContextPublisher;

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
                                        state.retryCount(), state.userInput(), state.userOptions()))
                ))
                .addNode(TeamGraphConstants.NODE_AGGREGATE, node_async(state ->
                        aggregate(state.memberResults(), state.retryCount())
                ))
                .addNode(TeamGraphConstants.NODE_RETRY, node_async(state ->
                        Map.of(TeamGraphConstants.STATE_RETRY_COUNT, state.retryCount() + 1)
                ))
                .addEdge(START, TeamGraphConstants.NODE_EXECUTE_MEMBERS)
                .addEdge(TeamGraphConstants.NODE_EXECUTE_MEMBERS, TeamGraphConstants.NODE_AGGREGATE)
                .addConditionalEdges(TeamGraphConstants.NODE_AGGREGATE,
                        edge_async(state -> AgentExecutionStatusEnum.FAILED.getCode().equals(state.finalStatus())
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
                                                     String userInput, Map<String, Object> userOptions) {
        List<CompletableFuture<Map<String, Object>>> futures = new ArrayList<>();
        for (AgentTeamMember member : members) {
            futures.add(executeMember(agent, execution, parentContext, member, retryCount, userInput, userOptions));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private CompletableFuture<Map<String, Object>> executeMember(Agent agent, AgentExecution parentExecution,
                                                                 AgentExecutionContext parentContext,
                                                                 AgentTeamMember member,
                                                                 int retryCount,
                                                                 String userInput,
                                                                 Map<String, Object> userOptions) {
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
                .inputPrompt(buildMemberPrompt(parentContext.getInputPrompt(), member, retryCount, userInput, userOptions))
                .inputContext(parentContext.getInputContext())
                .model(modelName)
                .agentModelType(AgentModelBindingTypeEnum.MODEL.getCode())
                .conversationId(childExecution.getConversationId())
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
        List<String> outputs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Map<String, Object> memberResult : memberResults) {
            String roleName = asText(memberResult.get(TeamGraphConstants.RESULT_ROLE_NAME));
            String status = asText(memberResult.get(TeamGraphConstants.RESULT_STATUS));
            String outputResult = asText(memberResult.get(TeamGraphConstants.RESULT_OUTPUT_RESULT));
            if (AgentExecutionStatusEnum.PAUSED.getCode().equals(status)) {
                pausedCount++;
            }
            if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status) && StringUtils.hasText(outputResult)) {
                successCount++;
            }
            if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(status) && !StringUtils.hasText(outputResult)) {
                warnings.add("[" + roleName + "] 执行已完成但未产生可用输出");
            }
            if (StringUtils.hasText(outputResult)) {
                outputs.add("[" + roleName + "]\n" + outputResult);
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
        if (successCount == 0) {
            update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.FAILED.getCode());
            List<String> failureMessages = new ArrayList<>(warnings);
            failureMessages.addAll(errors);
            update.put(TeamGraphConstants.STATE_FINAL_ERROR, StringUtils.collectionToDelimitedString(failureMessages, "\n"));
            return update;
        }
        String finalOutput = StringUtils.collectionToDelimitedString(outputs, "\n\n---\n\n");
        if (!warnings.isEmpty()) {
            finalOutput = appendWarnings(finalOutput, warnings);
        }
        update.put(TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode());
        update.put(TeamGraphConstants.STATE_FINAL_OUTPUT, finalOutput);
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
        DriverManager.registerDriver(new Driver());
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
        try (var connection = DriverManager.getConnection(datasourceUrl, datasourceUsername, datasourcePassword);
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

    private String buildPlanSummary(Agent agent, List<AgentTeamMember> members) {
        List<String> plans = members.stream()
                .map(member -> member.getRoleName() + "(" + member.getRoleType() + ")")
                .toList();
        return "Team Agent[" + agent.getName() + "] 成员分工: " + StringUtils.collectionToDelimitedString(plans, ", ");
    }

    String buildMemberPrompt(String originalPrompt, AgentTeamMember member, int retryCount,
                             String userInput, Map<String, Object> userOptions) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("你当前是 Team Agent 中的成员角色。\n");
        promptBuilder.append("角色名称: ").append(member.getRoleName()).append("\n");
        promptBuilder.append("角色类型: ").append(member.getRoleType()).append("\n");
        if (StringUtils.hasText(member.getDescription())) {
            promptBuilder.append("角色职责: ").append(member.getDescription()).append("\n");
        }
        if (retryCount > 0) {
            promptBuilder.append("这是基于反思后的再次执行，请修正前一轮问题并输出可汇总结果。\n");
        }
        if (StringUtils.hasText(userInput)) {
            promptBuilder.append("人工补充输入:\n").append(userInput).append("\n");
        }
        if (!CollectionUtils.isEmpty(userOptions)) {
            promptBuilder.append("人工补充选项:\n").append(userOptions).append("\n");
        }
        promptBuilder.append("\n原始任务:\n").append(originalPrompt);
        return promptBuilder.toString();
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
