package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AgentTeamChildResumeLockTest {
    @Test
    void skipsLockedChildThenClaimsItExactlyOnce() throws Exception {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("DB_TEST_URL"));
        source.setUser(System.getenv().getOrDefault("DB_USER", "schemaplexai"));
        source.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        var jdbc = new JdbcTemplate(source);
        // 测试由独立 schema 隔离，使用与部署环境一致的 VARCHAR 父执行和成员字段。
        String schema = "resume_claim_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            source.setCurrentSchema(schema);
            jdbc.execute("""
                    CREATE TABLE sf_agent_execution (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, agent_id UUID NOT NULL,
                        parent_execution_id VARCHAR(255), team_member_id VARCHAR(255), conversation_id VARCHAR(255),
                        status VARCHAR(32), started_at TIMESTAMP, deleted INTEGER DEFAULT 0
                    )
                    """);
            var tenant = UUID.randomUUID();
            var agent = UUID.randomUUID();
            var parent = UUID.randomUUID();
            var child = UUID.randomUUID();
            String member = UUID.randomUUID().toString();
            String conversation = "team:" + parent + ":" + member;
            jdbc.update("INSERT INTO sf_agent_execution (id, tenant_id, agent_id, status) VALUES (?, ?, ?, 'running')",
                    parent, tenant, agent);
            jdbc.update("""
                    INSERT INTO sf_agent_execution (id, tenant_id, agent_id, parent_execution_id, team_member_id, conversation_id, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'paused')
                    """, child, tenant, agent, parent.toString(), member, conversation);
            var configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(AgentExecutionMapper.class);
            var factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AgentExecutionMapper.class);
            var logs = new AgentLogService(mock(AgentExecutionLogMapper.class), mapper,
                    mock(ApplicationEventPublisher.class), mock(AgentTerminalEventOutbox.class));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            var context = AgentExecutionContext.builder().executionId(child.toString()).parentExecutionId(parent.toString())
                    .tenantId(tenant.toString()).agentId(agent.toString()).teamMemberId(member).conversationId(conversation).build();

            try (var lock = source.getConnection(); var statement = lock.prepareStatement(
                    "SELECT id FROM sf_agent_execution WHERE id = ? FOR UPDATE")) {
                lock.setAutoCommit(false);
                statement.setObject(1, child);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(transaction.<Boolean>execute(status -> logs.tryResumeTeamChild(context))).isFalse();
                } finally {
                    lock.rollback();
                }
            }

            assertThat(transaction.<Boolean>execute(status -> logs.tryResumeTeamChild(context))).isTrue();
            assertThat(transaction.<Boolean>execute(status -> logs.tryResumeTeamChild(context))).isFalse();
            assertThat(jdbc.queryForObject("SELECT status FROM sf_agent_execution WHERE id = ?", String.class, child))
                    .isEqualTo("running");
        } finally {
            source.setCurrentSchema("public");
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
