package com.schemaplexai.model.vo.semantic;

import java.time.LocalDateTime;

/** 语义模型目录视图。 */
public record SemanticModelVO(
        String id,
        String name,
        String domain,
        String description,
        String status,
        String activeVersionId,
        Integer activeVersionNo,
        Integer mappingCoverage,
        String validationStatus,
        long revision,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
