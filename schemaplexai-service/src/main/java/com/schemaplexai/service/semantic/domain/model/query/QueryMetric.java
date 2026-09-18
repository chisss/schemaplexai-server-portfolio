package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 查询指标，只保存语义和物理映射，不保存可执行 SQL。 */
public record QueryMetric(
        String iri,
        String alias,
        String aggregation,
        String physicalObject,
        String physicalField) {

    public QueryMetric {
        iri = requireText(iri, "iri");
        alias = requireText(alias, "alias");
        aggregation = requireText(aggregation, "aggregation");
        physicalObject = requireText(physicalObject, "physicalObject");
        physicalField = requireText(physicalField, "physicalField");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
