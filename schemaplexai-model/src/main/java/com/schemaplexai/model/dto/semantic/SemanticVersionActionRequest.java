package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.Min;

/** 校验或发布语义版本请求。 */
public record SemanticVersionActionRequest(
        @Min(value = 0, message = "revision不能小于0")
        long expectedRevision) {
}
