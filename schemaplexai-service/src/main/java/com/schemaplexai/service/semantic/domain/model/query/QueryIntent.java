package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 自然语言查询的安全中间表示，不允许携带原始查询语言。 */
public record QueryIntent(
        String semanticVersionId,
        String sourceId,
        List<QueryMetric> metrics,
        List<QueryDimension> dimensions,
        List<QueryFilter> filters,
        QueryTimeRange timeRange,
        int limit,
        QueryOutput output) {

    public QueryIntent {
        semanticVersionId = requireText(semanticVersionId, "semanticVersionId");
        sourceId = requireText(sourceId, "sourceId");
        metrics = copy(metrics);
        dimensions = copy(dimensions);
        filters = copy(filters);
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("at least one metric is required");
        }
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("limit must be between 1 and 5000");
        }
        output = Objects.requireNonNull(output, "output is required");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
