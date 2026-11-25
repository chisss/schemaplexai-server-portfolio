package com.schemaplexai.service.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.dao.mapper.ExecutionSteeringMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresChatMemoryArchiveTest {

    private static final String TENANT_ID = "40000000-0000-0000-0000-000000000001";
    private static final String PROJECT_ID = "40000000-0000-0000-0000-000000000002";

    @Test
    void shouldSerializeConcurrentAppendsAndRecoverLegacyAndCurrentMessages() throws Exception {
        String url = System.getenv("CHAT_MEMORY_DB_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "在隔离的 PostgreSQL schema 运行");
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "schemaplexai",
                System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sf_chat_message (
                    id varchar(255) PRIMARY KEY, tenant_id varchar(255), conversation_id varchar(255),
                    execution_id varchar(255), agent_id varchar(255), message_type varchar(255),
                    text_content text, message_payload bytea, tool_calls jsonb,
                    tool_call_id varchar(255), tool_name varchar(255), token_count integer,
                    message_index integer, turn_index integer, created_at timestamp, deleted integer DEFAULT 0
                )
                """);
        try {
            verifyArchive(dataSource, jdbc);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS sf_execution_steering");
            jdbc.execute("DROP TABLE IF EXISTS sf_chat_memory_extraction");
            jdbc.execute("DROP TABLE sf_chat_message");
            jdbc.execute("DROP TABLE IF EXISTS sf_agent_execution");
        }
    }

    private void verifyArchive(DriverManagerDataSource dataSource, JdbcTemplate jdbc) throws Exception {
        jdbc.execute("CREATE TABLE sf_agent_execution (id varchar(255) PRIMARY KEY, temporary_chat boolean, tenant_id uuid)");
        jdbc.update("INSERT INTO sf_agent_execution (id, temporary_chat, tenant_id) VALUES ('old', false, ?::uuid),"
                + " ('exec-a', true, ?::uuid), ('exec-b', false, ?::uuid)", TENANT_ID, TENANT_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO sf_chat_message (id, conversation_id, execution_id, message_type, text_content, message_index)
                VALUES ('legacy', 'conversation', 'old', 'USER', '旧会话', 0)
                """);
        ResourceDatabasePopulator migration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/agent-chat-memory-archive.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);
        jdbc.update("INSERT INTO sf_chat_memory_extraction (execution_id, extractor) VALUES ('old', 'USER')");
        var tenantMigration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/agent-memory-extraction-tenant.sql"));
        tenantMigration.execute(dataSource);
        tenantMigration.execute(dataSource);
        var projectMigration = new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/agent-execution-memory-project.sql"));
        projectMigration.execute(dataSource);
        projectMigration.execute(dataSource);
        assertThat(jdbc.queryForObject("SELECT tenant_id::text FROM sf_chat_memory_extraction WHERE execution_id = 'old'", String.class))
                .isEqualTo(TENANT_ID);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(ChatMessageMapper.class);
        configuration.addMapper(ExecutionSteeringMapper.class);
        MybatisSqlSessionFactoryBean sessionFactory = new MybatisSqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        sessionFactory.setConfiguration(configuration);
        var tenantInterceptor = new MybatisPlusInterceptor();
        tenantInterceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(TENANT_ID);
            }

            @Override
            public boolean ignoreTable(String tableName) {
                return !"sf_chat_memory_extraction".equals(tableName);
            }
        }));
        sessionFactory.setPlugins(tenantInterceptor);
        SqlSessionTemplate session = new SqlSessionTemplate(sessionFactory.getObject());
        ChatMessageMapper messages = session.getMapper(ChatMessageMapper.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        when(executions.selectById("exec-a")).thenReturn(owner("exec-a"));
        when(executions.selectById("exec-b")).thenReturn(owner("exec-b"));
        PostgresChatMemoryStore store = new PostgresChatMemoryStore(executions, messages);
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        UserMessage picture = UserMessage.from(TextContent.from("用户文字附附件"),
                ImageContent.from("AQID", "image/png"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> append(transaction, store, barrier, "exec-a",
                List.of(new ArchivedMessage(picture, "用户本人的文字"))));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> append(transaction, store, barrier, "exec-b",
                List.of(new ArchivedMessage(UserMessage.from("第二个用户输入"), "第二个用户输入"),
                        new ArchivedMessage(UserMessage.from("合成续写提示"), null),
                        new ArchivedMessage(AiMessage.from("模型回复"), null))));
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertThat(jdbc.queryForList("SELECT message_index FROM sf_chat_message ORDER BY message_index", Integer.class))
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(jdbc.queryForList("SELECT DISTINCT turn_index FROM sf_chat_message ORDER BY turn_index", Integer.class))
                .containsExactly(1, 2, 3);
        assertThat(store.loadWindow("conversation", "exec-a", 20)).contains(UserMessage.from("旧会话"), picture);
        assertThat(store.loadWindow("conversation", "unrelated", 20)).doesNotContain(picture);
        jdbc.update("UPDATE sf_agent_execution SET memory_project_id = ?::uuid WHERE id = 'exec-b'", PROJECT_ID);
        jdbc.update("INSERT INTO sf_agent_execution (id, memory_project_id) VALUES ('project-next', ?::uuid)", PROJECT_ID);
        assertThat(store.loadWindow("conversation", "project-next", 20))
                .contains(UserMessage.from("第二个用户输入")).doesNotContain(UserMessage.from("旧会话"), picture);
        assertThat(store.loadWindow("conversation", "unrelated", 20))
                .contains(UserMessage.from("旧会话")).doesNotContain(UserMessage.from("第二个用户输入"));
        assertThat(store.loadExecutionForExtraction("conversation", "exec-b"))
                .containsExactly(UserMessage.from("第二个用户输入"), AiMessage.from("模型回复"));
        assertThat(store.loadExecutionForExtraction("conversation", "exec-a"))
                .containsExactly(UserMessage.from("用户本人的文字"));

        var synthetic = IntStream.range(0, 101)
                .mapToObj(index -> new ArchivedMessage(UserMessage.from("合成提示 " + index), null))
                .toList();
        transaction.executeWithoutResult(status -> store.appendMessages("conversation", "exec-b", synthetic));
        assertThat(store.loadExecutionForExtraction("conversation", "exec-b")).isEmpty();
        assertThat(store.loadAuthoredExecutionInputs("conversation", "exec-b"))
                .containsExactly(UserMessage.from("第二个用户输入"));
        assertThat(store.loadAuthoredExecutionInputs("conversation", "exec-a"))
                .containsExactly(UserMessage.from("用户本人的文字"));

        ToolExecutionResultMessage result = ToolExecutionResultMessage.from("call", "test", "result");
        transaction.executeWithoutResult(status -> store.appendMissingToolResults("conversation", "exec-b", List.of(result)));
        transaction.executeWithoutResult(status -> store.appendMissingToolResults("conversation", "exec-b", List.of(result)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sf_chat_message WHERE tool_call_id = 'call'", Long.class))
                .isEqualTo(1);

        MemoryExtractionWriter writer = new MemoryExtractionWriter(messages);
        assertThatThrownBy(() -> transaction.execute(status -> writer.writeOnce("exec-b",
                MemoryExtractionWriter.Extractor.AGENT, List.of("first try"), ignored -> 0)))
                .isInstanceOf(IllegalStateException.class);
        List<String> retried = transaction.execute(status -> writer.writeOnce("exec-b",
                MemoryExtractionWriter.Extractor.AGENT, List.of("second try"), ignored -> 1));
        List<String> duplicate = transaction.execute(status -> writer.writeOnce("exec-b",
                MemoryExtractionWriter.Extractor.AGENT, List.of("duplicate"), ignored -> 1));
        assertThat(retried).containsExactly("second try");
        assertThat(duplicate).isEmpty();
        assertThat(jdbc.queryForObject("SELECT tenant_id::text FROM sf_chat_memory_extraction WHERE execution_id = 'exec-b'", String.class))
                .isEqualTo(TENANT_ID);
        verifySteeringArchive(jdbc, transactionManager, session.getMapper(ExecutionSteeringMapper.class), store);
    }

    private void verifySteeringArchive(JdbcTemplate jdbc, DataSourceTransactionManager transactionManager,
                                       ExecutionSteeringMapper mapper, PostgresChatMemoryStore store) {
        jdbc.execute("""
                CREATE TABLE sf_execution_steering (
                    id varchar(255) PRIMARY KEY, tenant_id varchar(255), execution_id varchar(255),
                    status varchar(32), seq integer, content text, applied_at timestamp,
                    updated_at timestamp, updated_by varchar(255), deleted integer DEFAULT 0
                )
                """);
        jdbc.update("""
                INSERT INTO sf_execution_steering (id, tenant_id, execution_id, status, seq, content)
                VALUES ('steer-a', 'tenant', 'exec-a', 'pending', 1, '仅运行已验收的步骤')
                """);
        jdbc.execute("""
                ALTER TABLE sf_chat_message ADD CONSTRAINT reject_steering_input
                CHECK (authored_text IS DISTINCT FROM '仅运行已验收的步骤')
                """);
        var composite = new CompositeChatMemoryStore(store, mock(RedisChatMemoryStore.class));
        var proxy = new ProxyFactory(new ExecutionSteeringService(mapper, composite));
        proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
        ExecutionSteeringService service = (ExecutionSteeringService) proxy.getProxy();
        ExecutionSteering steering = new ExecutionSteering();
        steering.setId("steer-a");
        steering.setTenantId("tenant");
        steering.setExecutionId("exec-a");
        steering.setContent("仅运行已验收的步骤");
        var memory = new ArchivedChatMemory("conversation", "exec-a", composite,
                MessageWindowChatMemory.withMaxMessages(20), 20);
        UserMessage wrapped = UserMessage.from(ExecutionSteeringService.buildSteeringWrapper(steering.getContent()));

        assertThatThrownBy(() -> service.applyAndArchive(steering, "conversation"))
                .hasMessageContaining("reject_steering_input");
        assertThat(jdbc.queryForObject("SELECT status FROM sf_execution_steering WHERE id='steer-a'", String.class))
                .isEqualTo("pending");
        assertThat(store.loadAuthoredExecutionInputs("conversation", "exec-a"))
                .doesNotContain(UserMessage.from(steering.getContent()));
        assertThat(memory.messages()).doesNotContain(wrapped);

        jdbc.execute("ALTER TABLE sf_chat_message DROP CONSTRAINT reject_steering_input");
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            assertThat(service.applyAndArchive(steering, "conversation")).contains(wrapped);
            outer.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT status FROM sf_execution_steering WHERE id='steer-a'", String.class))
                .isEqualTo("applied");
        assertThat(memory.messages()).doesNotContain(wrapped);
        memory.addArchivedUserInput(wrapped);
        assertThat(memory.messages()).last().isEqualTo(wrapped);
        assertThat(service.applyAndArchive(steering, "conversation")).isEmpty();
        var resumed = new ArchivedChatMemory("conversation", "exec-a", composite,
                MessageWindowChatMemory.withMaxMessages(20), 20);
        assertThat(resumed.messages()).filteredOn(wrapped::equals).containsExactly(wrapped);
        assertThat(store.loadAuthoredExecutionInputs("conversation", "exec-a"))
                .filteredOn(UserMessage.from(steering.getContent())::equals)
                .containsExactly(UserMessage.from(steering.getContent()));
    }

    private void append(TransactionTemplate transaction, PostgresChatMemoryStore store, CyclicBarrier barrier,
                        String executionId, List<ArchivedMessage> messages) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            transaction.executeWithoutResult(status -> store.appendMessages("conversation", executionId, messages));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private AgentExecution owner(String executionId) {
        AgentExecution execution = new AgentExecution();
        execution.setId(executionId);
        execution.setConversationId("conversation");
        execution.setTenantId("tenant");
        execution.setAgentId("agent");
        return execution;
    }
}
