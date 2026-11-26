package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.Min;
import com.schemaplexai.common.enums.OntologyReleasePurpose;

/** 校验或发布语义版本请求。 */
public record SemanticVersionActionRequest(
        @Min(value = 0, message = "revision不能小于0")
        long expectedRevision,
        OntologyReleasePurpose purpose) {

    public SemanticVersionActionRequest(long expectedRevision) {
        this(expectedRevision, OntologyReleasePurpose.CONTEXT);
    }

    public OntologyReleasePurpose effectivePurpose() {
        return purpose == null ? OntologyReleasePurpose.CONTEXT : purpose;
    }
}
