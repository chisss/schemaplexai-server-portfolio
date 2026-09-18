package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.Pattern;

/** 创建语义版本请求，Phase 1 允许从空白或 Schema 快照创建。 */
public record SemanticVersionCreateRequest(
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$",
                message = "Schema快照ID格式不正确")
        String sourceSnapshotId) {
}
