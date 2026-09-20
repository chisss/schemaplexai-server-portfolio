package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 固定语义版本和数据源的可审计查询计划。 */
public record QueryPlan(
        String semanticVersionId,
        String sourceId,
        String databaseType,
        QueryDialect dialect,
        CompiledQuery query,
        String planHash,
        List<String> warnings) {

    public QueryPlan {
        semanticVersionId = requireText(semanticVersionId, "semanticVersionId");
        sourceId = requireText(sourceId, "sourceId");
        databaseType = requireText(databaseType, "databaseType");
        dialect = Objects.requireNonNull(dialect, "dialect is required");
        query = Objects.requireNonNull(query, "query is required");
        planHash = requireText(planHash, "planHash");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
