package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Map;

/** 受控执行结果，列和血缘来自已签名计划。 */
public record QueryExecutionResult(
        String planHash,
        List<Map<String, Object>> rows,
        List<QueryColumn> columns,
        List<QueryLineage> lineage,
        int rowCount,
        boolean truncated,
        long elapsedMs,
        String auditId,
        List<String> warnings) {

    public QueryExecutionResult {
        rows = rows == null ? List.of() : rows.stream().map(Map::copyOf).toList();
        columns = columns == null ? List.of() : List.copyOf(columns);
        lineage = lineage == null ? List.of() : List.copyOf(lineage);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (rowCount < 0 || elapsedMs < 0) {
            throw new IllegalArgumentException("rowCount and elapsedMs must not be negative");
        }
    }
}
