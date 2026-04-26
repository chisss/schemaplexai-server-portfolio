package com.schemaplexai.service.clickhouse;

import java.time.LocalDateTime;

/**
 * ClickHouse 同步游标
 */
public record ClickHouseSyncCursor(LocalDateTime cursorTime, String cursorId) {
}
