package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 计划中语义字段到物理字段的血缘绑定。 */
public record QueryLineage(
        String semanticIri,
        String sourceId,
        String physicalObject,
        String physicalField) {

    public QueryLineage {
        semanticIri = requireText(semanticIri, "semanticIri");
        sourceId = requireText(sourceId, "sourceId");
        physicalObject = requireText(physicalObject, "physicalObject");
        physicalField = requireText(physicalField, "physicalField");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
