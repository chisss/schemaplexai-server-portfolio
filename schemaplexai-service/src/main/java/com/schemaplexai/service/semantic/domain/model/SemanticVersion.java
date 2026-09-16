package com.schemaplexai.service.semantic.domain.model;

import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Objects;

/** 语义版本聚合根，所有状态转换和 revision 变更在此完成。 */
@Getter
public final class SemanticVersion {

    private final String id;
    private final String tenantId;
    private final String modelId;
    private final int versionNo;
    private final String graphIri;
    private SemanticVersionStatus status;
    private String checksum;
    private long tripleCount;
    private String validationReport;
    private LocalDateTime publishedAt;
    private long revision;

    private SemanticVersion(
            String id,
            String tenantId,
            String modelId,
            int versionNo,
            String graphIri,
            SemanticVersionStatus status,
            String checksum,
            long tripleCount,
            String validationReport,
            LocalDateTime publishedAt,
            long revision) {
        this.id = requireText(id, "id");
        this.tenantId = requireText(tenantId, "tenantId");
        this.modelId = requireText(modelId, "modelId");
        if (versionNo < 1) {
            throw new IllegalArgumentException("versionNo must be positive");
        }
        this.versionNo = versionNo;
        this.graphIri = requireText(graphIri, "graphIri");
        this.status = Objects.requireNonNull(status, "status is required");
        this.checksum = normalizeOptional(checksum);
        if (tripleCount < 0) {
            throw new IllegalArgumentException("tripleCount must not be negative");
        }
        this.tripleCount = tripleCount;
        this.validationReport = normalizeOptional(validationReport);
        this.publishedAt = publishedAt;
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        this.revision = revision;
    }

    public static SemanticVersion createDraft(
            String id,
            String tenantId,
            String modelId,
            int versionNo,
            String graphIri) {
        return new SemanticVersion(
                id,
                tenantId,
                modelId,
                versionNo,
                graphIri,
                SemanticVersionStatus.DRAFT,
                null,
                0,
                null,
                null,
                0);
    }

    public static SemanticVersion restore(
            String id,
            String tenantId,
            String modelId,
            int versionNo,
            String graphIri,
            SemanticVersionStatus status,
            String checksum,
            long tripleCount,
            String validationReport,
            LocalDateTime publishedAt,
            long revision) {
        return new SemanticVersion(
                id,
                tenantId,
                modelId,
                versionNo,
                graphIri,
                status,
                checksum,
                tripleCount,
                validationReport,
                publishedAt,
                revision);
    }

    public boolean isEditable() {
        return status == SemanticVersionStatus.DRAFT || status == SemanticVersionStatus.INVALID;
    }

    public void startValidation(long expectedRevision) {
        requireRevision(expectedRevision);
        if (!isEditable()) {
            throw invalidTransition(SemanticVersionStatus.VALIDATING);
        }
        status = SemanticVersionStatus.VALIDATING;
        validationReport = null;
        revision++;
    }

    public void markInvalid(String report, long expectedRevision) {
        requireRevision(expectedRevision);
        requireStatus(SemanticVersionStatus.VALIDATING, SemanticVersionStatus.INVALID);
        validationReport = requireText(report, "report");
        status = SemanticVersionStatus.INVALID;
        revision++;
    }

    public void publish(
            String checksum,
            long tripleCount,
            LocalDateTime publishedAt,
            long expectedRevision) {
        requireRevision(expectedRevision);
        requireStatus(SemanticVersionStatus.VALIDATING, SemanticVersionStatus.PUBLISHED);
        if (tripleCount < 0) {
            throw new IllegalArgumentException("tripleCount must not be negative");
        }
        this.checksum = requireText(checksum, "checksum");
        this.tripleCount = tripleCount;
        this.publishedAt = Objects.requireNonNull(publishedAt, "publishedAt is required");
        this.validationReport = null;
        this.status = SemanticVersionStatus.PUBLISHED;
        revision++;
    }

    public void archive(long expectedRevision) {
        requireRevision(expectedRevision);
        requireStatus(SemanticVersionStatus.PUBLISHED, SemanticVersionStatus.ARCHIVED);
        status = SemanticVersionStatus.ARCHIVED;
        revision++;
    }

    private void requireStatus(SemanticVersionStatus current, SemanticVersionStatus target) {
        if (status != current) {
            throw invalidTransition(target);
        }
    }

    private IllegalStateException invalidTransition(SemanticVersionStatus target) {
        return new IllegalStateException("cannot transition semantic version from " + status + " to " + target);
    }

    private void requireRevision(long expectedRevision) {
        if (revision != expectedRevision) {
            throw new IllegalStateException("semantic version revision conflict");
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
