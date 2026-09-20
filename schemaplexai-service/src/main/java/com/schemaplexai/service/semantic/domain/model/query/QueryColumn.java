package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 计划结果列及其语义来源。 */
public record QueryColumn(String role, String alias, String expression, String semanticIri) {

    public QueryColumn {
        role = requireText(role, "role");
        alias = requireText(alias, "alias");
        expression = requireText(expression, "expression");
        semanticIri = requireText(semanticIri, "semanticIri");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
