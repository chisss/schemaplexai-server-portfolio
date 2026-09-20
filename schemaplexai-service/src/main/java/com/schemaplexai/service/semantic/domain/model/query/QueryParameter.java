package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 参数化查询的单个绑定值。值不会被拼接进查询文本。 */
public record QueryParameter(String name, String value) {

    public QueryParameter {
        name = requireText(name, "name");
        value = Objects.requireNonNull(value, "value is required");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
