package com.schemaplexai.service.semantic.domain.model;

import com.schemaplexai.service.semantic.common.SchemaSnapshotStatus;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;

import java.time.LocalDateTime;
import java.util.Objects;

/** 租户数据源的不可变 Schema 快照。 */
public final class SchemaSnapshot {

    private final String id;
    private final String tenantId;
    private final String sourceId;
    private final String fingerprint;
    private final DatabaseSchema schema;
    private final SchemaSnapshotStatus status;
    private final LocalDateTime scannedAt;

    private SchemaSnapshot(
            String id,
            String tenantId,
            String sourceId,
            String fingerprint,
            DatabaseSchema schema,
            SchemaSnapshotStatus status,
            LocalDateTime scannedAt) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.sourceId = requireText(sourceId, "sourceId");
        this.fingerprint = requireText(fingerprint, "fingerprint");
        this.schema = Objects.requireNonNull(schema, "schema is required");
        this.status = Objects.requireNonNull(status, "status is required");
        this.scannedAt = Objects.requireNonNull(scannedAt, "scannedAt is required");
    }

    public static SchemaSnapshot create(
            String id,
            String tenantId,
            String sourceId,
            String fingerprint,
            DatabaseSchema schema,
            LocalDateTime scannedAt) {
        SchemaSnapshotStatus status = schema.partial()
                ? SchemaSnapshotStatus.PARTIAL
                : SchemaSnapshotStatus.READY;
        return new SchemaSnapshot(id, tenantId, sourceId, fingerprint, schema, status, scannedAt);
    }

    public static SchemaSnapshot restore(
            String id,
            String tenantId,
            String sourceId,
            String fingerprint,
            DatabaseSchema schema,
            SchemaSnapshotStatus status,
            LocalDateTime scannedAt) {
        return new SchemaSnapshot(id, tenantId, sourceId, fingerprint, schema, status, scannedAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getSourceId() { return sourceId; }
    public String getFingerprint() { return fingerprint; }
    public DatabaseSchema getSchema() { return schema; }
    public SchemaSnapshotStatus getStatus() { return status; }
    public LocalDateTime getScannedAt() { return scannedAt; }
}
