package com.schemaplexai.service.semantic.domain.model;

import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import lombok.Getter;

import java.util.Objects;

/** 语义模型聚合根，维护模型元数据与活动版本不变量。 */
@Getter
public final class SemanticModel {

    private final String id;
    private final String tenantId;
    private String name;
    private String domain;
    private String description;
    private String activeVersionId;
    private SemanticModelStatus status;
    private long revision;

    private SemanticModel(
            String id,
            String tenantId,
            String name,
            String domain,
            String description,
            String activeVersionId,
            SemanticModelStatus status,
            long revision) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.name = requireText(name, "name");
        this.domain = requireText(domain, "domain");
        this.description = normalizeOptional(description);
        this.activeVersionId = normalizeOptional(activeVersionId);
        this.status = Objects.requireNonNull(status, "status is required");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
    }

    public static SemanticModel create(
            String id,
            String tenantId,
            String name,
            String domain,
            String description) {
        return new SemanticModel(
                id,
                tenantId,
                name,
                domain,
                description,
                null,
                SemanticModelStatus.ACTIVE,
                0);
    }

    public static SemanticModel restore(
            String id,
            String tenantId,
            String name,
            String domain,
            String description,
            String activeVersionId,
            SemanticModelStatus status,
            long revision) {
        return new SemanticModel(
                id,
                tenantId,
                name,
                domain,
                description,
                activeVersionId,
                status,
                revision);
    }

    public void updateProfile(String name, String domain, String description, long expectedRevision) {
        requireActive();
        requireRevision(expectedRevision);
        this.name = requireText(name, "name");
        this.domain = requireText(domain, "domain");
        this.description = normalizeOptional(description);
        revision++;
    }

    public void activateVersion(String versionId, long expectedRevision) {
        requireActive();
        requireRevision(expectedRevision);
        this.activeVersionId = requireText(versionId, "versionId");
        revision++;
    }

    public void archive(long expectedRevision) {
        requireActive();
        requireRevision(expectedRevision);
        if (activeVersionId != null) {
            throw new IllegalStateException("model with an active version cannot be archived");
        }
        status = SemanticModelStatus.ARCHIVED;
        revision++;
    }

    private void requireActive() {
        if (status != SemanticModelStatus.ACTIVE) {
            throw new IllegalStateException("archived model cannot be modified");
        }
    }

    private void requireRevision(long expectedRevision) {
        if (revision != expectedRevision) {
            throw new IllegalStateException("semantic model revision conflict");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
