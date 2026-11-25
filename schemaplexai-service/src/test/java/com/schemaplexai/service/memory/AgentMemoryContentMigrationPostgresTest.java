package com.schemaplexai.service.memory;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AgentMemoryContentMigrationPostgresTest {

    @Test
    void shouldStoreFullAgentMemoryAndKeepLegacyRowsAfterRepeatableMigration() throws Exception {
        String url = System.getenv("DB_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "需要 PostgreSQL 测试连接");
        var dataSource = new DriverManagerDataSource(url, "schemaplexai",
                System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        String migration = new ClassPathResource("db/migration/agent-memory-content-text.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        String extracted = "需求事实".repeat(125);
        String consolidated = "合并结果".repeat(125);

        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TEMP TABLE sf_agent_memory (
                        id integer PRIMARY KEY, tenant_id text NOT NULL, agent_id text NOT NULL,
                        phase text NOT NULL, content varchar(255) NOT NULL
                    )
                    """);
            insert(connection, 1, "tenant-a", "RAW", "历史记忆");
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insert(connection, 2, "tenant-a", "RAW", extracted))
                    .satisfies(error -> assertThat(error.getSQLState()).isEqualTo("22001"));

            statement.execute(migration);
            insert(connection, 2, "tenant-a", "RAW", extracted);
            insert(connection, 3, "tenant-b", "CONSOLIDATED", consolidated);
            statement.execute(migration);

            try (var rows = statement.executeQuery("SELECT * FROM sf_agent_memory ORDER BY id")) {
                assertThat(rows.getMetaData().getColumnTypeName(5)).isEqualTo("text");
                for (var expected : new String[][]{
                        {"tenant-a", "RAW", "历史记忆"},
                        {"tenant-a", "RAW", extracted},
                        {"tenant-b", "CONSOLIDATED", consolidated}}) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("tenant_id")).isEqualTo(expected[0]);
                    assertThat(rows.getString("agent_id")).isEqualTo("agent-1");
                    assertThat(rows.getString("phase")).isEqualTo(expected[1]);
                    assertThat(rows.getString("content")).isEqualTo(expected[2]);
                }
                assertThat(rows.next()).isFalse();
            }
        }
    }

    private void insert(Connection connection, int id, String tenant, String phase, String content) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO sf_agent_memory (id, tenant_id, agent_id, phase, content) VALUES (?, ?, 'agent-1', ?, ?)
                """)) {
            statement.setInt(1, id);
            statement.setString(2, tenant);
            statement.setString(3, phase);
            statement.setString(4, content);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }
}
