package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 已完成方言编译但尚未执行的查询。 */
public record CompiledQuery(
        String language,
        String statement,
        List<QueryParameter> parameters,
        List<QueryColumn> columns,
        List<QueryLineage> lineage) {

    public CompiledQuery {
        language = requireText(language, "language");
        statement = requireText(statement, "statement");
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        columns = columns == null ? List.of() : List.copyOf(columns);
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
