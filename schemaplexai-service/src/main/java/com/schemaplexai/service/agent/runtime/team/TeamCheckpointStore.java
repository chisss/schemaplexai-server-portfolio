package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Slf4j
@Component
@RequiredArgsConstructor
public class TeamCheckpointStore {

    private static final String SCHEMA_SQL = """
            SELECT current_schema() AS schema_name,
                   to_regclass('lg4jthread') IS NOT NULL AS thread_exists,
                   to_regclass('lg4jcheckpoint') IS NOT NULL AS checkpoint_exists,
                   (to_regclass('lg4jthread') IS DISTINCT FROM
                    to_regclass(format('%I.lg4jthread', current_schema())))
                   OR (to_regclass('lg4jcheckpoint') IS DISTINCT FROM
                       to_regclass(format('%I.lg4jcheckpoint', current_schema()))) AS uses_fallback_schema,
                   (SELECT format_type(atttypid, atttypmod)
                    FROM pg_attribute
                    WHERE attrelid = to_regclass('lg4jcheckpoint') AND attname = 'state_data'
                      AND NOT attisdropped) AS state_type
            """;

    private static final String SCHEMA_LOCK_SQL = """
            SELECT pg_advisory_xact_lock(hashtext(current_database()), hashtext(current_schema() || '.lg4jcheckpoint'))
            """;

    private static final ClassPathResource SAVER_SCHEMA = new ClassPathResource(
            "db/migration/v1.1__init.sql", PostgresSaver.class.getClassLoader());

    private static final String EXPIRE_SQL = """
            WITH expired AS (
                SELECT t.thread_id
                FROM lg4jthread t
                JOIN sf_agent_execution e ON e.graph_thread_id = t.thread_name
                WHERE t.is_released
                  AND e.runtime_engine = ?
                  AND e.status IN (?, ?, ?, ?, ?)
                  AND e.completed_at < localtimestamp - make_interval(days => ?)
                  AND NOT EXISTS (
                      SELECT 1 FROM lg4jcheckpoint c
                      WHERE c.thread_id = t.thread_id
                        AND c.saved_at >= current_timestamp - make_interval(days => ?))
                ORDER BY e.completed_at
                LIMIT ?
                FOR UPDATE OF t SKIP LOCKED
            )
            DELETE FROM lg4jthread t USING expired WHERE t.thread_id = expired.thread_id
            """;

    private static final int CLEANUP_BATCH_SIZE = 250;

    private final DataSource dataSource;

    @Value("${schemaplexai.agent.team.checkpoint.retention-days:30}")
    private int retentionDays = 30;

    @EventListener(ApplicationStartedEvent.class)
    public void verifySchema() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            tablesReady(connection);
        }
    }

    PostgresSaver buildSaver(StateGraph<TeamGraphState> graph) throws Exception {
        ensureTables();
        return PostgresSaver.builder()
                .datasource(dataSource)
                .stateSerializer(graph.getStateSerializer())
                .build();
    }

    private void ensureTables() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (tablesReady(connection)) {
                return;
            }
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute(SCHEMA_LOCK_SQL);
                if (!tablesReady(connection)) {
                    ScriptUtils.executeSqlScript(connection, SAVER_SCHEMA);
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private boolean tablesReady(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(SCHEMA_SQL)) {
            if (!result.next() || result.getString("schema_name") == null) {
                throw new IllegalStateException("Team checkpoint 需要有效的 PostgreSQL current_schema()");
            }
            String schemaName = result.getString("schema_name");
            if (result.getBoolean("uses_fallback_schema")) {
                throw new IllegalStateException("Team checkpoint 表位于 search_path 的后备 schema，请确认主 schema " + schemaName);
            }
            boolean checkpointExists = result.getBoolean("checkpoint_exists");
            if (checkpointExists && !"text".equals(result.getString("state_type"))) {
                throw new IllegalStateException("Team checkpoint schema " + schemaName
                        + " 的 lg4jcheckpoint.state_data 需要 TEXT，请在部署前执行 langgraph-checkpoint-1.9.sql");
            }
            return result.getBoolean("thread_exists") && checkpointExists;
        }
    }

    @Scheduled(fixedDelayString = "${schemaplexai.agent.team.checkpoint.cleanup-interval-ms:3600000}",
            initialDelayString = "${schemaplexai.agent.team.checkpoint.cleanup-interval-ms:3600000}")
    public void cleanupExpiredCheckpoints() throws SQLException {
        if (retentionDays <= 0) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!tablesReady(connection)) {
                return;
            }
            try (var statement = connection.prepareStatement(EXPIRE_SQL)) {
                statement.setString(1, AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
                statement.setString(2, AgentExecutionStatusEnum.COMPLETED.getCode());
                statement.setString(3, AgentExecutionStatusEnum.COMPLETED_WITH_FALLBACK.getCode());
                statement.setString(4, AgentExecutionStatusEnum.FAILED.getCode());
                statement.setString(5, AgentExecutionStatusEnum.STOPPED.getCode());
                statement.setString(6, AgentExecutionStatusEnum.CANCELLED.getCode());
                statement.setInt(7, retentionDays);
                statement.setInt(8, retentionDays);
                statement.setInt(9, CLEANUP_BATCH_SIZE);
                int deleted = statement.executeUpdate();
                if (deleted > 0) {
                    log.info("清理已释放且过期的 Team checkpoint 线程: count={}", deleted);
                }
            }
        }
    }
}
