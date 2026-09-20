package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;

/** EXPLAIN 阶段只返回计划元数据，不返回业务数据。 */
public record QueryExplainResult(
        String planHash,
        String language,
        String statement,
        List<String> parameterNames,
        List<QueryColumn> columns,
        List<QueryLineage> lineage,
        List<String> warnings) {

    public QueryExplainResult {
        parameterNames = parameterNames == null ? List.of() : List.copyOf(parameterNames);
        columns = columns == null ? List.of() : List.copyOf(columns);
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
