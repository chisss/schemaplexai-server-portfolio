package com.schemaplexai.service.agent.execution;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class AgentHarnessPostgres implements AutoCloseable {
    static final String TENANT = "2d2235f8-3adf-4f89-8be3-942e9861188a";
    static final String OTHER_TENANT = "9bdf21ca-d5a5-4789-995b-697deae3d009";
    static final String EXECUTION = "6b580e50-a96a-4d05-8a3b-564278f48edd";

    private final JdbcTemplate admin;
    private final String schema = "agent_harness_" + UUID.randomUUID().toString().replace("-", "");
    final JdbcTemplate jdbc;
    final SimpleDriverDataSource dataSource;

    AgentHarnessPostgres() {
        String url = System.getenv("AGENT_HARNESS_DB_URL");
        assumeTrue(url != null && !url.isBlank(), "未配置隔离的 PostgreSQL 验收库");
        String user = System.getenv().getOrDefault("AGENT_HARNESS_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("AGENT_HARNESS_DB_PASSWORD", "");
        admin = new JdbcTemplate(new SimpleDriverDataSource(new Driver(), url, user, password));
        admin.execute("CREATE SCHEMA " + schema);
        dataSource = new SimpleDriverDataSource(new Driver(), url, user, password);
        var properties = new Properties();
        properties.setProperty("currentSchema", schema);
        dataSource.setConnectionProperties(properties);
        jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("""
                    CREATE TABLE sf_agent_execution (
                        id uuid PRIMARY KEY, tenant_id uuid NOT NULL, parent_execution_id varchar(255),
                        status varchar(32) NOT NULL, deleted integer NOT NULL DEFAULT 0,
                        token_input bigint, token_output bigint, runtime_elapsed_millis bigint
                    )
                    """);
            var migration = new ResourceDatabasePopulator(
                    new ClassPathResource("db/migration/agent-execution-harness.sql"),
                    new ClassPathResource("db/migration/agent-harness-reliability.sql"));
            migration.execute(dataSource);
            migration.execute(dataSource);
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    String schema() {
        return schema;
    }

    void insert(String status) {
        jdbc.update("INSERT INTO sf_agent_execution (id, tenant_id, status) VALUES (?::uuid, ?::uuid, ?)",
                EXECUTION, TENANT, status);
    }

    @Override
    public void close() {
        admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }
}
