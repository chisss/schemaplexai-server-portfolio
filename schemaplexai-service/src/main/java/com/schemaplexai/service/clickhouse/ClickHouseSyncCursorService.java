package com.schemaplexai.service.clickhouse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ClickHouse 同步游标持久化服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseSyncCursorService {

    private static final String SYNC_NAME_COST_RECORD = "clickhouse_cost_record";
    private static final ClickHouseSyncCursor INITIAL_CURSOR = new ClickHouseSyncCursor(
            LocalDateTime.of(1970, 1, 1, 0, 0), ""
    );

    private final JdbcTemplate jdbcTemplate;

    /**
     * 加载成本明细同步游标
     */
    public ClickHouseSyncCursor loadCostRecordCursor() {
        ensureSchema();
        List<ClickHouseSyncCursor> cursors = jdbcTemplate.query(
                "SELECT cursor_time, cursor_id FROM sf_sync_cursor WHERE sync_name = ?",
                (rs, rowNum) -> new ClickHouseSyncCursor(
                        rs.getTimestamp("cursor_time").toLocalDateTime(),
                        rs.getString("cursor_id")
                ),
                SYNC_NAME_COST_RECORD
        );
        return cursors.isEmpty() ? INITIAL_CURSOR : cursors.getFirst();
    }

    /**
     * 保存成本明细同步游标
     */
    public void saveCostRecordCursor(ClickHouseSyncCursor cursor) {
        if (cursor == null || cursor.cursorTime() == null || !StringUtils.hasText(cursor.cursorId())) {
            return;
        }
        ensureSchema();
        jdbcTemplate.update("""
                INSERT INTO sf_sync_cursor(sync_name, cursor_time, cursor_id, updated_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (sync_name)
                DO UPDATE SET cursor_time = EXCLUDED.cursor_time,
                              cursor_id = EXCLUDED.cursor_id,
                              updated_at = CURRENT_TIMESTAMP
                """,
                SYNC_NAME_COST_RECORD,
                Timestamp.valueOf(cursor.cursorTime()),
                cursor.cursorId()
        );
        log.info("ClickHouse 成本同步游标已推进: cursorTime={}, cursorId={}", cursor.cursorTime(), cursor.cursorId());
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sf_sync_cursor (
                    sync_name VARCHAR(100) PRIMARY KEY,
                    cursor_time TIMESTAMP NOT NULL,
                    cursor_id VARCHAR(64) NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
    }
}
