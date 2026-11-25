package com.schemaplexai.service.agent.runtime.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
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
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.AgentLoopQualityChecker;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.TeamExecutionBudget;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.mq.AgentContextPublisher;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamAgentRuntimeStrategyCheckpointTest {

    private static final String DATASOURCE_URL = System.getenv().getOrDefault(
            "DB_TEST_URL", "jdbc:postgresql://localhost:5432/schemaplexai?stringtype=unspecified");
    private static final String DATASOURCE_USERNAME = System.getenv().getOrDefault("DB_USER", "schemaplexai");
    private static final String DATASOURCE_PASSWORD = System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123");

    private String schema;

    @BeforeEach
    void createSchema() throws Exception {
        schema = "checkpoint_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    @Test
    void shouldMigrateLegacyPayloadWithoutLosingState() throws Exception {
        var serializer = new StateGraph<TeamGraphState>(TeamGraphState::new).getStateSerializer();
        // 由 LangGraph4j 1.8.13 的 ObjectStreamStateSerializer 生成，验证真实旧载荷兼容性。
        String payload = "rO0ABXcPAAAAAQAAAAZtYXJrZXIBdAAY5Y2H57qn5YmN5pqC5YGc55qE5Lu75Yqh";
        try (var connection = DriverManager.getConnection(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SET LOCAL search_path TO " + schema + ", public");
                statement.execute("CREATE TABLE lg4jcheckpoint (state_data JSONB NOT NULL, state_content_type TEXT NOT NULL)");
                try (var insert = connection.prepareStatement(
                        "INSERT INTO lg4jcheckpoint VALUES (jsonb_build_object('binaryPayload', ?::text), ?)")) {
                    insert.setString(1, payload);
                    insert.setString(2, serializer.contentType());
                    insert.executeUpdate();
                }

                statement.execute(checkpointMigrationSql());
                statement.execute(checkpointMigrationSql());

                try (var result = statement.executeQuery(
                        "SELECT state_data, state_content_type, pg_typeof(state_data)::text FROM lg4jcheckpoint")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(payload);
                    assertThat(serializer.readDataFromString(result.getString(1)).data())
                            .containsEntry("marker", "升级前暂停的任务");
                    assertThat(result.getString(2)).isEqualTo(serializer.contentType());
                    assertThat(result.getString(3)).isEqualTo("text");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static String checkpointMigrationSql() throws Exception {
        return new ClassPathResource("db/migration/langgraph-checkpoint-1.9.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 32, 64})
    void shouldStoreFallbackStatusAfterRepeatableMigration(int statusWidth) throws Exception {
        String migration = new ClassPathResource("db/migration/agent-execution-status.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        try (var connection = buildCheckpointDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE sf_agent_execution (id integer PRIMARY KEY, status varchar(%d) NOT NULL DEFAULT 'queued')"
                    .formatted(statusWidth));
            statement.execute("CREATE INDEX idx_execution_status ON sf_agent_execution(status)");
            statement.execute("INSERT INTO sf_agent_execution (id, status) VALUES (1, 'running'), (2, 'completed')");
            statement.execute(migration);
            statement.execute(migration);

            String fallback = AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode();
            try (var update = connection.prepareStatement("UPDATE sf_agent_execution SET status=? WHERE status='running'")) {
                update.setString(1, fallback);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            statement.execute("INSERT INTO sf_agent_execution (id) VALUES (3)");
            try (var records = statement.executeQuery("SELECT status FROM sf_agent_execution ORDER BY id")) {
                assertThat(records.getMetaData().getPrecision(1)).isEqualTo(Math.max(statusWidth, 32));
                var statuses = new ArrayList<String>();
                while (records.next()) {
                    statuses.add(records.getString(1));
                }
                assertThat(statuses).containsExactly(fallback, AgentExecutionStatusEnum.COMPLETED.getCode(),
                        AgentExecutionStatusEnum.QUEUED.getCode());
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (var connection = DriverManager.getConnection(DATASOURCE_URL, DATASOURCE_USERNAME, DATASOURCE_PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "release_failure", "missing_checkpoint", "attachment", "attachment_revoked"})
    void shouldResumePausedExecutionWithRealCheckpointState(String scenario) throws Exception {
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentTeamMemberMapper agentTeamMemberMapper = mock(AgentTeamMemberMapper.class);
        AgentTeamMemberToolBindingMapper teamMemberToolBindingMapper = mock(AgentTeamMemberToolBindingMapper.class);
        BuiltinToolMapper builtinToolMapper = mock(BuiltinToolMapper.class);
        AgentTeamMemberContextBindingMapper teamMemberContextBindingMapper = mock(AgentTeamMemberContextBindingMapper.class);
        ContextItemMapper contextItemMapper = mock(ContextItemMapper.class);
        AgentExecutionEngine agentExecutionEngine = mock(AgentExecutionEngine.class);
        AgentLogService agentLogService = mock(AgentLogService.class);
        when(agentLogService.tryTransitionExecutionStatus(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);
        AgentLoopQualityChecker qualityChecker = mock(AgentLoopQualityChecker.class);
        ExecutionEventStreamService executionEventStreamService = mock(ExecutionEventStreamService.class);
        AgentContextPublisher agentContextPublisher = mock(AgentContextPublisher.class);
        DesktopAttachmentService desktopAttachments = mock(DesktopAttachmentService.class);
        List<String> attachmentIds = scenario.startsWith("attachment")
                ? List.of("tenants/test/desktop-chat/upload/image.png") : List.of();
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
                new TeamCheckpointStore(buildCheckpointDataSource()),
                desktopAttachments,
                mock(SandboxPolicyResolver.class),
                mock(ExecutionSteeringService.class),
                new DefaultTeamQualityDetector(),
                new TeamConversationArchive(mock(CompositeChatMemoryStore.class), mock(UserMemoryExtractionService.class), new ObjectMapper()),
                mock(TeamChildApprovalService.class), mock(TeamExecutionBudget.class),
                Runnable::run
        );

        Agent agent = new Agent();
        agent.setId(UUID.randomUUID().toString());
        agent.setName("Checkpoint Team");

        AgentExecution parentExecution = new AgentExecution();
        parentExecution.setId(UUID.randomUUID().toString());
        parentExecution.setAgentId(agent.getId());
        parentExecution.setTenantId("70f3a15f-3955-4a7b-bc95-e3558f0f620f");
        parentExecution.setInputPrompt("请先调用工具检查当前目录，再汇总安全风险。");
        parentExecution.setAiModel("mock-team-model");
        parentExecution.setCreatedBy("parent-owner");
        parentExecution.setConversationId(attachmentIds.isEmpty()
                ? "team-parent-" + UUID.randomUUID() : "desktop-" + UUID.randomUUID());
        String threadName = "team-" + parentExecution.getId();

        AgentTeamMember member = new AgentTeamMember();
        member.setId(UUID.randomUUID().toString());
        member.setAgentId(agent.getId());
        member.setRoleName("Lead Planner");
        member.setRoleType("lead_agent");
        member.setDescription("负责汇总并等待人工确认后继续执行");
        member.setSortOrder(1);

        when(teamMemberToolBindingMapper.selectList(any())).thenReturn(List.of());
        if (attachmentIds.isEmpty()) {
            when(agentTeamMemberMapper.selectList(any())).thenReturn(List.of(member));
            when(teamMemberContextBindingMapper.selectList(any())).thenReturn(List.of());
        } else {
            AgentTeamMember contributor = new AgentTeamMember();
            contributor.setId(UUID.randomUUID().toString());
            contributor.setRoleName("有证据的成员");
            contributor.setRoleType("member");
            AgentTeamMemberContextBinding binding = new AgentTeamMemberContextBinding();
            binding.setMemberId(contributor.getId());
            when(agentTeamMemberMapper.selectList(any())).thenReturn(List.of(member, contributor));
            when(teamMemberContextBindingMapper.selectList(any())).thenReturn(List.of(binding));
        }

        doAnswer(invocation -> {
            AgentExecution childExecution = invocation.getArgument(0);
            if (!StringUtils.hasText(childExecution.getId())) {
                childExecution.setId(UUID.randomUUID().toString());
            }
            return true;
        }).when(agentLogService).createTeamChildWhileParentRunning(any(AgentExecution.class));

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
            if (!member.getId().equals(childContext.getTeamMemberId())) {
                assertThat(childContext.getAttachmentIds()).isNullOrEmpty();
                assertThat(childContext.getAttachmentSourceConversationId()).isNull();
                return CompletableFuture.completedFuture(AgentExecutionResult.builder()
                        .status(AgentExecutionStatusEnum.COMPLETED.getCode())
                        .outputResult("成员提供摘要")
                        .build());
            }
            if (attachmentIds.isEmpty()) {
                assertThat(childContext.getAttachmentIds()).isNullOrEmpty();
            } else {
                assertThat(childContext.getAttachmentIds()).containsExactlyElementsOf(attachmentIds);
                assertThat(childContext.getAttachmentSourceConversationId()).isEqualTo(parentExecution.getConversationId());
                assertThat(childContext.getUserId()).isEqualTo(parentExecution.getCreatedBy());
            }
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
                .inputContext(Map.of("attachmentIds", List.of("another-user/forged.png")))
                .attachmentIds(attachmentIds)
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
        resumeInput.setOptions(Map.of("approvalMode", "manual", "resumeStep", "security_review",
                "attachmentIds", List.of("another-user/forged.png")));

        // 恢复路径上下文由编排器构建（含 sandboxPolicy/executionMode），此处模拟编排器产物
        AgentExecutionContext resumeContext = AgentExecutionContext.builder()
                .executionId(parentExecution.getId())
                .agentId(agent.getId())
                .tenantId(parentExecution.getTenantId())
                .userId(parentExecution.getCreatedBy())
                .inputPrompt(parentExecution.getInputPrompt())
                .model(parentExecution.getAiModel())
                .conversationId(parentExecution.getConversationId())
                .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                .executionMode("auto")
                .build();

        if ("release_failure".equals(scenario)) {
            LangGraphTeamRuntimeAdapter adapter = spy(new LangGraphTeamRuntimeAdapter());
            doThrow(new IllegalStateException("释放不可用")).when(adapter).releaseCheckpoint(any(), any());
            ReflectionTestUtils.setField(strategy, "langGraphRuntimeAdapter", adapter);
        }
        if ("missing_checkpoint".equals(scenario)) {
            parentExecution.setGraphThreadId("missing-" + UUID.randomUUID());
        }
        if ("attachment_revoked".equals(scenario)) {
            doThrow(new BusinessException(ResultCode.FORBIDDEN, "附件已被撤销")).when(desktopAttachments)
                    .validateObjectKeys(attachmentIds, true, parentExecution.getTenantId(), parentExecution.getCreatedBy());
        }
        AgentExecutionResult resumedResult = strategy.resume(agent, parentExecution, resumeContext, resumeInput).get();
        if (!attachmentIds.isEmpty()) {
            verify(desktopAttachments, times(2))
                    .validateObjectKeys(attachmentIds, true, parentExecution.getTenantId(), parentExecution.getCreatedBy());
        }
        if ("missing_checkpoint".equals(scenario) || "attachment_revoked".equals(scenario)) {
            assertThat(executionRound.get()).isEqualTo(1);
            assertThat(resumedResult.getStatus()).isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
            verify(agentLogService).tryTransitionExecutionStatus(eq(parentExecution.getId()),
                    eq(AgentExecutionStatusEnum.RUNNING.getCode()), eq(AgentExecutionStatusEnum.FAILED.getCode()),
                    any(), isNull(), isNull(), isNull());
            return;
        }
        verify(agentLogService, never()).tryTransitionExecutionStatus(eq(parentExecution.getId()), any(),
                eq(AgentExecutionStatusEnum.FAILED.getCode()), any(), any(), any(), any());
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
        assertThat(resumedState.released()).isEqualTo(!"release_failure".equals(scenario));
        assertThat(resumedState.checkpointCount()).isGreaterThan(0);
    }

    @Test
    void shouldResumeGraphFromCheckpointsGeneratedByVersion1813() throws Exception {
        DataSource dataSource = buildCheckpointDataSource();
        TeamCheckpointStore store = new TeamCheckpointStore(dataSource);
        var graph = new StateGraph<>(TeamGraphState::new)
                .addNode(TeamGraphConstants.NODE_PLAN, node_async(state -> Map.of()))
                .addNode(TeamGraphConstants.NODE_MEMBER_CYCLE, node_async(state -> Map.of()))
                .addNode(TeamGraphConstants.NODE_AWAIT_INPUT, node_async(state -> Map.of(
                        TeamGraphConstants.STATE_FINAL_STATUS, AgentExecutionStatusEnum.COMPLETED.getCode(),
                        TeamGraphConstants.STATE_FINAL_OUTPUT, state.userInput())))
                .addEdge(START, TeamGraphConstants.NODE_PLAN)
                .addEdge(TeamGraphConstants.NODE_PLAN, TeamGraphConstants.NODE_MEMBER_CYCLE)
                .addEdge(TeamGraphConstants.NODE_MEMBER_CYCLE, TeamGraphConstants.NODE_AWAIT_INPUT)
                .addEdge(TeamGraphConstants.NODE_AWAIT_INPUT, END);
        store.buildSaver(graph);
        // 这三条记录由 1.8.13 Core + PostgreSQL Saver 实际执行到 interruptBefore 时产生。
        String[] checkpoints = new ClassPathResource("checkpoint/langgraph4j-1.8.13-paused-team.tsv")
                .getContentAsString(StandardCharsets.UTF_8).split("\\R");
        UUID threadId = UUID.randomUUID();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var insertThread = connection.prepareStatement("INSERT INTO lg4jthread VALUES (?, ?, false)");
             var insertCheckpoint = connection.prepareStatement("""
                     INSERT INTO lg4jcheckpoint (
                         checkpoint_id, thread_id, node_id, next_node_id, state_data, state_content_type, saved_at
                     ) VALUES (?, ?, ?, ?, jsonb_build_object('binaryPayload', ?::text), ?, ?)
                     """)) {
            statement.execute("ALTER TABLE lg4jcheckpoint ALTER COLUMN state_data TYPE JSONB USING state_data::jsonb");
            insertThread.setObject(1, threadId);
            insertThread.setString(2, "team-legacy-fixture");
            insertThread.executeUpdate();
            for (int index = 0; index < checkpoints.length; index++) {
                String[] columns = checkpoints[index].split("\t");
                insertCheckpoint.setObject(1, UUID.randomUUID());
                insertCheckpoint.setObject(2, threadId);
                insertCheckpoint.setString(3, columns[0]);
                insertCheckpoint.setString(4, columns[1]);
                insertCheckpoint.setString(5, columns[2]);
                insertCheckpoint.setString(6, columns[3]);
                insertCheckpoint.setTimestamp(7, Timestamp.from(Instant.EPOCH.plusSeconds(index)));
                insertCheckpoint.executeUpdate();
            }
            statement.execute(checkpointMigrationSql());
        }

        var adapter = new LangGraphTeamRuntimeAdapter();
        var compileConfig = adapter.buildCompileConfig(store.buildSaver(graph));
        var compiled = graph.compile(compileConfig);
        var config = RunnableConfig.builder().threadId("team-legacy-fixture").build();

        TeamGraphState paused = adapter.lastStateOf(compiled, config).orElseThrow();
        assertThat(paused.finalStatus()).isEqualTo(AgentExecutionStatusEnum.PAUSED.getCode());
        assertThat(paused.memberResults()).hasSize(1);
        assertThat(paused.data()).containsEntry(TeamGraphConstants.STATE_INPUT_PROMPT, "升级前暂停的任务");
        TeamGraphState resumed = adapter.resume(compiled, config, "升级后人工输入", Map.of("approvalMode", "manual"))
                .orElseThrow();
        assertThat(resumed.finalStatus()).isEqualTo(AgentExecutionStatusEnum.COMPLETED.getCode());
        assertThat(resumed.finalOutput()).isEqualTo("升级后人工输入");
        assertThat(resumed.userOptions()).containsEntry("approvalMode", "manual");
        adapter.releaseCheckpoint(compileConfig, config);
        assertThat(loadCheckpointState("team-legacy-fixture").released()).isTrue();
    }

    @Test
    void shouldRejectLegacyCheckpointInConfiguredSchemaBeforeBuildingSaver() throws Exception {
        DataSource dataSource = buildCheckpointDataSource();
        TeamCheckpointStore store = new TeamCheckpointStore(dataSource);
        store.verifySchema();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lg4jthread (thread_id UUID PRIMARY KEY)");
            statement.execute("CREATE TABLE lg4jcheckpoint (state_data JSONB NOT NULL)");
        }
        assertThatThrownBy(store::verifySchema)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(schema)
                .hasMessageContaining("langgraph-checkpoint-1.9.sql");

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(checkpointMigrationSql());
        }
        store.verifySchema();
    }

    @Test
    void shouldPreserveLegacyTableWhenMigrationFindsInvalidPayload() throws Exception {
        try (var connection = buildCheckpointDataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lg4jcheckpoint (state_data JSONB NOT NULL)");
            statement.execute("INSERT INTO lg4jcheckpoint VALUES (jsonb_build_object('binaryPayload', false))");
            assertThatThrownBy(() -> statement.execute(checkpointMigrationSql()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("缺少 binaryPayload 字符串");
            try (var result = statement.executeQuery("""
                    SELECT pg_typeof(state_data)::text, state_data ->> 'binaryPayload' FROM lg4jcheckpoint
                    """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("jsonb");
                assertThat(result.getString(2)).isEqualTo("false");
            }
        }
    }

    @Test
    void shouldAvoidCheckpointTablesInFallbackSearchPath() throws Exception {
        DataSource dataSource = buildCheckpointDataSource();
        TeamCheckpointStore store = new TeamCheckpointStore(dataSource);
        store.buildSaver(new StateGraph<TeamGraphState>(TeamGraphState::new));
        String fallbackSchema = schema;
        String preferredSchema = schema + "_main";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + preferredSchema);
        }
        try {
            var fallbackDataSource = buildCheckpointDataSource();
            fallbackDataSource.setCurrentSchema(preferredSchema + "," + fallbackSchema);
            var fallbackStore = new TeamCheckpointStore(fallbackDataSource);
            assertThatThrownBy(fallbackStore::verifySchema)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("search_path")
                    .hasMessageContaining(preferredSchema);
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA " + preferredSchema + " CASCADE");
            }
        }
    }

    @Test
    void shouldExpireOnlyOldReleasedTerminalThreadsAndTheirCheckpoints() throws Exception {
        DataSource dataSource = buildCheckpointDataSource();
        TeamCheckpointStore store = new TeamCheckpointStore(dataSource);
        ReflectionTestUtils.setField(store, "retentionDays", 7);
        store.buildSaver(new StateGraph<TeamGraphState>(TeamGraphState::new));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE sf_agent_execution (
                        graph_thread_id TEXT,
                        runtime_engine TEXT,
                        status TEXT,
                        completed_at TIMESTAMP
                    )
                    """);
        }
        seedCheckpoint(dataSource, "expired", true, AgentExecutionStatusEnum.COMPLETED, 14);
        seedCheckpoint(dataSource, "still-active", false, AgentExecutionStatusEnum.COMPLETED, 14);
        seedCheckpoint(dataSource, "paused", true, AgentExecutionStatusEnum.PAUSED, 14);
        seedCheckpoint(dataSource, "fresh", true, AgentExecutionStatusEnum.COMPLETED, 1);

        store.cleanupExpiredCheckpoints();
        store.cleanupExpiredCheckpoints();

        assertThat(loadCheckpointState("expired").exists()).isFalse();
        assertThat(loadCheckpointState("still-active").checkpointCount()).isEqualTo(1);
        assertThat(loadCheckpointState("paused").checkpointCount()).isEqualTo(1);
        assertThat(loadCheckpointState("fresh").checkpointCount()).isEqualTo(1);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM lg4jcheckpoint")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(3);
        }
    }

    private void seedCheckpoint(DataSource dataSource, String name, boolean released,
                                AgentExecutionStatusEnum status, int ageDays) throws Exception {
        UUID threadId = UUID.randomUUID();
        try (var connection = dataSource.getConnection();
             var thread = connection.prepareStatement("INSERT INTO lg4jthread VALUES (?, ?, ?)");
             var checkpoint = connection.prepareStatement("""
                     INSERT INTO lg4jcheckpoint (checkpoint_id, thread_id, state_data, state_content_type, saved_at)
                     VALUES (?, ?, '', 'test', current_timestamp - make_interval(days => ?))
                     """);
             var execution = connection.prepareStatement("""
                     INSERT INTO sf_agent_execution VALUES (?, ?, ?, localtimestamp - make_interval(days => ?))
                     """)) {
            thread.setObject(1, threadId);
            thread.setString(2, name);
            thread.setBoolean(3, released);
            thread.executeUpdate();
            checkpoint.setObject(1, UUID.randomUUID());
            checkpoint.setObject(2, threadId);
            checkpoint.setInt(3, ageDays);
            checkpoint.executeUpdate();
            execution.setString(1, name);
            execution.setString(2, AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
            execution.setString(3, status.getCode());
            execution.setInt(4, ageDays);
            execution.executeUpdate();
        }
    }

    private CheckpointState loadCheckpointState(String threadName) throws Exception {
        String sql = """
                select t.is_released, count(c.checkpoint_id) as checkpoint_count
                from lg4jthread t
                left join lg4jcheckpoint c on c.thread_id = t.thread_id
                where t.thread_name = ?
                group by t.is_released
                """;
        try (var connection = buildCheckpointDataSource().getConnection();
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

    private PGSimpleDataSource buildCheckpointDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(DATASOURCE_URL);
        dataSource.setCurrentSchema(schema);
        dataSource.setUser(DATASOURCE_USERNAME);
        dataSource.setPassword(DATASOURCE_PASSWORD);
        return dataSource;
    }

    private record CheckpointState(boolean exists, boolean released, long checkpointCount) {
    }
}
