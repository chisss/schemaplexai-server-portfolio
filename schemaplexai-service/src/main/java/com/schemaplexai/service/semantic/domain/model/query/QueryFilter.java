package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 查询过滤条件，值只来自用户问题或澄清选项。 */
public record QueryFilter(
        String iri,
        String alias,
        String operator,
        String value,
        String physicalObject,
        String physicalField) {

    public QueryFilter {
        iri = requireText(iri, "iri");
        alias = requireText(alias, "alias");
        operator = requireText(operator, "operator");
        value = requireText(value, "value");
        physicalObject = requireText(physicalObject, "physicalObject");
        physicalField = requireText(physicalField, "physicalField");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
