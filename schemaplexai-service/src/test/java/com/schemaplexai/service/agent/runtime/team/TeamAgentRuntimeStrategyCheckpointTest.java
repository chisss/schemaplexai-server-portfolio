package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
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
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.mq.AgentContextPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentRuntimeStrategyCheckpointTest {

    private static final String DATASOURCE_URL = "jdbc:postgresql://localhost:5432/schemaplexai?stringtype=unspecified";
    private static final String DATASOURCE_USERNAME = "schemaplexai";
    private static final String DATASOURCE_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123");

    private final AtomicReference<String> cleanupThreadName = new AtomicReference<>();

    @AfterEach
    void tearDown() throws Exception {
        if (StringUtils.hasText(cleanupThreadName.get())) {
            deleteCheckpointThread(cleanupThreadName.get());
        }
    }

    @Test
    void shouldResumePausedExecutionWithRealCheckpointState() throws Exception {
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentTeamMemberMapper agentTeamMemberMapper = mock(AgentTeamMemberMapper.class);
        AgentTeamMemberToolBindingMapper teamMemberToolBindingMapper = mock(AgentTeamMemberToolBindingMapper.class);
        BuiltinToolMapper builtinToolMapper = mock(BuiltinToolMapper.class);
        AgentTeamMemberContextBindingMapper teamMemberContextBindingMapper = mock(AgentTeamMemberContextBindingMapper.class);
        ContextItemMapper contextItemMapper = mock(ContextItemMapper.class);
        AgentExecutionEngine agentExecutionEngine = mock(AgentExecutionEngine.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        AgentLoopQualityChecker qualityChecker = mock(AgentLoopQualityChecker.class);
        ExecutionEventStreamService executionEventStreamService = mock(ExecutionEventStreamService.class);
        AgentContextPublisher agentContextPublisher = mock(AgentContextPublisher.class);
        when(qualityChecker.buildImmediateFeedback(any(), any(), any(), anyInt(), any())).thenReturn(null);

        TeamAgentRuntimeStrategy strategy = new TeamAgentRuntimeStrategy(
                agentExecutionMapper,
                agentTeamMemberMapper,
                teamMemberToolBindingMapper,
                builtinToolMapper,
                teamMemberContextBindingMapper,
                contextItemMapper,
                agentExecutionEngine,
                agentLogService,
                qualityChecker,
                executionEventStreamService,
                agentContextPublisher,
                buildCheckpointDataSource()
        );
        ReflectionTestUtils.setField(strategy, "datasourceUrl", DATASOURCE_URL);
        ReflectionTestUtils.setField(strategy, "datasourceUsername", DATASOURCE_USERNAME);
        ReflectionTestUtils.setField(strategy, "datasourcePassword", DATASOURCE_PASSWORD);

        Agent agent = new Agent();
        agent.setId(UUID.randomUUID().toString());
        agent.setName("Checkpoint Team");

        AgentExecution parentExecution = new AgentExecution();
        parentExecution.setId(UUID.randomUUID().toString());
        parentExecution.setAgentId(agent.getId());
        parentExecution.setTenantId("70f3a15f-3955-4a7b-bc95-e3558f0f620f");
        parentExecution.setInputPrompt("请先调用工具检查当前目录，再汇总安全风险。");
        parentExecution.setAiModel("mock-team-model");
        parentExecution.setConversationId("team-parent-" + UUID.randomUUID());
        String threadName = "team-" + parentExecution.getId();
        cleanupThreadName.set(threadName);
        deleteCheckpointThread(threadName);

        AgentTeamMember member = new AgentTeamMember();
        member.setId(UUID.randomUUID().toString());
        member.setAgentId(agent.getId());
        member.setRoleName("Lead Planner");
        member.setRoleType("lead_agent");
        member.setDescription("负责汇总并等待人工确认后继续执行");
        member.setSortOrder(1);

        when(agentTeamMemberMapper.selectList(any())).thenReturn(List.of(member));
        when(teamMemberToolBindingMapper.selectList(any())).thenReturn(List.of());
        when(teamMemberContextBindingMapper.selectList(any())).thenReturn(List.of());

        doAnswer(invocation -> {
            AgentExecution childExecution = invocation.getArgument(0);
            if (!StringUtils.hasText(childExecution.getId())) {
                childExecution.setId(UUID.randomUUID().toString());
            }
            return 1;
        }).when(agentExecutionMapper).insert(any(AgentExecution.class));

        doAnswer(invocation -> {
            AgentExecution update = invocation.getArgument(0);
            if (parentExecution.getId().equals(update.getId())) {
                if (StringUtils.hasText(update.getGraphThreadId())) {
                    parentExecution.setGraphThreadId(update.getGraphThreadId());
                }
                if (StringUtils.hasText(update.getCheckpointNamespace())) {
                    parentExecution.setCheckpointNamespace(update.getCheckpointNamespace());
                }
            }
            return 1;
        }).when(agentExecutionMapper).updateById(any(AgentExecution.class));

        AtomicInteger executionRound = new AtomicInteger();
        AtomicReference<String> resumedPrompt = new AtomicReference<>();
        when(agentExecutionEngine.execute(any(AgentExecutionContext.class))).thenAnswer(invocation -> {
            AgentExecutionContext childContext = invocation.getArgument(0);
            int round = executionRound.incrementAndGet();
            if (round == 1) {
                return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                        .status(AgentExecutionStatusEnum.PAUSED.getCode())
                        .errorMessage("成员工具执行命中安全复核，等待人工确认")
                        .conversationId("child-conv-1")
                        .build());
            }
            resumedPrompt.set(childContext.getInputPrompt());
            return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                    .status(AgentExecutionStatusEnum.COMPLETED.getCode())
                    .outputResult("已根据人工补充输入输出审批后的安全方案")
                    .conversationId("child-conv-2")
                    .build());
        });

        AgentExecutionContext initialContext = AgentExecutionContext.builder()
                .executionId(parentExecution.getId())
                .agentId(agent.getId())
                .tenantId(parentExecution.getTenantId())
                .inputPrompt(parentExecution.getInputPrompt())
                .inputContext(Map.of())
                .model(parentExecution.getAiModel())
                .conversationId(parentExecution.getConversationId())
                .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                .build();

        AgentExecutionResult pausedResult = strategy.execute(agent, parentExecution, initialContext).get();
        assertThat(pausedResult.getStatus()).isEqualTo(AgentExecutionStatusEnum.PAUSED.getCode());
        verify(agentLogService, times(1)).appendLog(
                eq(parentExecution.getId()),
                eq(agent.getId()),
                eq(parentExecution.getTenantId()),
                eq("INFO"),
                eq(AgentExecutionEventTypeEnum.ROUND_START.getCode()),
                isNull(),
                isNull(),
                contains("Team Agent 开始编排成员执行"),
                isNull(),
                isNull()
        );
        verify(agentLogService).appendLog(
                eq(parentExecution.getId()),
                eq(agent.getId()),
                eq(parentExecution.getTenantId()),
                eq("WARN"),
                eq(AgentExecutionEventTypeEnum.REQUIRE_INPUT.getCode()),
                isNull(),
                isNull(),
                contains("Team Agent 等待人工输入"),
                isNull(),
                isNull()
        );
        CheckpointState pausedState = loadCheckpointState(threadName);
        assertThat(pausedState.exists()).isTrue();
        assertThat(pausedState.released()).isFalse();
        assertThat(pausedState.checkpointCount()).isGreaterThan(0);

        AgentExecutionInputDTO resumeInput = new AgentExecutionInputDTO();
        resumeInput.setMessage("人工已确认可以继续，请只输出审批后的安全方案。");
        resumeInput.setOptions(Map.of("approvalMode", "manual", "resumeStep", "security_review"));

        AgentExecutionResult resumedResult = strategy.resume(agent, parentExecution, resumeInput).get();
        assertThat(resumedResult.getStatus()).isEqualTo(AgentExecutionStatusEnum.COMPLETED.getCode());
        assertThat(resumedResult.getOutputResult()).contains("审批后的安全方案");
        assertThat(resumedPrompt.get())
                .contains("人工补充输入")
                .contains("人工已确认可以继续")
                .contains("approvalMode")
                .contains("resumeStep");
        verify(agentLogService).appendLog(
                eq(parentExecution.getId()),
                eq(agent.getId()),
                eq(parentExecution.getTenantId()),
                eq("INFO"),
                eq(AgentExecutionEventTypeEnum.USER_INPUT.getCode()),
                isNull(),
                isNull(),
                contains("收到人工输入"),
                isNull(),
                isNull()
        );
        verify(agentLogService).appendLog(
                eq(parentExecution.getId()),
                eq(agent.getId()),
                eq(parentExecution.getTenantId()),
                eq("INFO"),
                eq(AgentExecutionEventTypeEnum.RESUMED.getCode()),
                isNull(),
                isNull(),
                contains("Team Agent 恢复编排执行"),
                isNull(),
                isNull()
        );
        verify(agentLogService).appendLog(
                eq(parentExecution.getId()),
                eq(agent.getId()),
                eq(parentExecution.getTenantId()),
                eq("INFO"),
                eq(AgentExecutionEventTypeEnum.COMPLETED.getCode()),
                isNull(),
                isNull(),
                contains("Team Agent 执行完成"),
                isNull(),
                isNull()
        );

        CheckpointState resumedState = loadCheckpointState(threadName);
        assertThat(resumedState.exists()).isTrue();
        assertThat(resumedState.released()).isTrue();
        assertThat(resumedState.checkpointCount()).isGreaterThan(0);
    }

    private CheckpointState loadCheckpointState(String threadName) throws Exception {
        String sql = """
                select t.is_released, count(c.checkpoint_id) as checkpoint_count
                from public.lg4jthread t
                left join public.lg4jcheckpoint c on c.thread_id = t.thread_id
                where t.thread_name = ?
                group by t.is_released
                """;
        try (var connection = DriverManager.getConnection(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD);
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, threadName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new CheckpointState(false, false, 0);
                }
                return new CheckpointState(true,
                        resultSet.getBoolean("is_released"),
                        resultSet.getLong("checkpoint_count"));
            }
        }
    }

    private void deleteCheckpointThread(String threadName) throws Exception {
        String deleteCheckpointSql = """
                delete from public.lg4jcheckpoint
                where thread_id in (select thread_id from public.lg4jthread where thread_name = ?)
                """;
        String deleteThreadSql = "delete from public.lg4jthread where thread_name = ?";
        try (var connection = DriverManager.getConnection(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD);
             var deleteCheckpoint = connection.prepareStatement(deleteCheckpointSql);
             var deleteThread = connection.prepareStatement(deleteThreadSql)) {
            deleteCheckpoint.setString(1, threadName);
            deleteCheckpoint.executeUpdate();
            deleteThread.setString(1, threadName);
            deleteThread.executeUpdate();
        }
    }

    private DataSource buildCheckpointDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(DATASOURCE_URL);
        dataSource.setUser(DATASOURCE_USERNAME);
        dataSource.setPassword(DATASOURCE_PASSWORD);
        return dataSource;
    }

    private record CheckpointState(boolean exists, boolean released, long checkpointCount) {
    }
}
