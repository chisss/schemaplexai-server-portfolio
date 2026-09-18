package com.schemaplexai.model.vo.semantic;

import java.time.LocalDateTime;

/** 语义模型版本视图。 */
public record SemanticVersionVO(
        String id,
        String modelId,
        int versionNo,
        String status,
        String sourceSnapshotId,
        String checksum,
        long tripleCount,
        String validationReport,
        long revision,
        LocalDateTime publishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
