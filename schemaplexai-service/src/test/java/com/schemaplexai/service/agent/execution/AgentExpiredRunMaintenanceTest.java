package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AgentExpiredRunMaintenanceTest {
    @Test
    void failsStaleRootAndChildrenAndKeepsLiveLease() {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(System.getenv("DB_TEST_URL"));
        source.setUser(System.getenv().getOrDefault("DB_USER", "schemaplexai"));
        source.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String schema = "agent_expired_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            source.setCurrentSchema(schema);
            jdbc.execute("""
                    CREATE TABLE sf_agent_execution (
                        id UUID PRIMARY KEY, tenant_id UUID NOT NULL, parent_execution_id VARCHAR(255),
                        deleted INTEGER DEFAULT 0, status VARCHAR(32) NOT NULL, runtime_engine VARCHAR(32),
                        harness_owner UUID, harness_lease_until TIMESTAMPTZ, started_at TIMESTAMP,
                        created_at TIMESTAMP NOT NULL DEFAULT localtimestamp,
                        completed_at TIMESTAMP, error_message TEXT
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE sf_agent_terminal_event (
                        execution_id UUID PRIMARY KEY, tenant_id UUID NOT NULL, payload TEXT NOT NULL
                    )
                    """);
            UUID tenant = UUID.randomUUID();
            UUID stale = UUID.randomUUID();
            UUID child = UUID.randomUUID();
            UUID running = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO sf_agent_execution (id, tenant_id, status, runtime_engine, started_at, created_at)
                    VALUES (?, ?, 'running', 'team_langgraph4j', localtimestamp - interval '1 day', localtimestamp - interval '1 day')
                    """, stale, tenant);
            jdbc.update("""
                    INSERT INTO sf_agent_execution (id, tenant_id, status, parent_execution_id)
                    VALUES (?, ?, 'paused', ?)
                    """, child, tenant, stale.toString());
            jdbc.update("""
                    INSERT INTO sf_agent_execution (id, tenant_id, status, harness_owner, harness_lease_until)
                    VALUES (?, ?, 'running', gen_random_uuid(), current_timestamp + interval '1 minute')
                    """, running, tenant);
            var outbox = new AgentTerminalEventOutbox(jdbc, new ObjectMapper().findAndRegisterModules());
            var stopSignal = mock(ExecutionStopSignal.class);
            var maintenance = new AgentExpiredRunMaintenance(jdbc, outbox, stopSignal);

            maintenance.failExpiredRuns();
            maintenance.failExpiredRuns();

            verify(stopSignal).publishAfterCommit(stale.toString());
            verify(stopSignal).publishAfterCommit(child.toString());
            verifyNoMoreInteractions(stopSignal);
            assertThat(jdbc.queryForList("SELECT id FROM sf_agent_execution WHERE status = 'failed'", UUID.class))
                    .containsExactlyInAnyOrder(stale, child);
            assertThat(jdbc.queryForList("SELECT execution_id FROM sf_agent_terminal_event", UUID.class))
                    .containsExactlyInAnyOrder(stale, child);
            assertThat(jdbc.queryForObject("SELECT status FROM sf_agent_execution WHERE id = ?", String.class, running))
                    .isEqualTo("running");
        } finally {
            source.setCurrentSchema("public");
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
