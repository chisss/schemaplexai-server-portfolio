package com.schemaplexai.model.vo.semantic;

import java.time.LocalDateTime;

/** Schema 快照摘要，不返回样例业务数据。 */
public record SchemaSnapshotVO(
        String id,
        String sourceId,
        String sourceName,
        String databaseType,
        String fingerprint,
        String status,
        int objectCount,
        int fieldCount,
        boolean driftDetected,
        int warningCount,
        String errorMessage,
        LocalDateTime scannedAt,
        LocalDateTime createdAt) {
}
