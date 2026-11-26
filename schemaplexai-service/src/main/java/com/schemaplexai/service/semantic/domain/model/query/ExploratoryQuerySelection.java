package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;

/** 探索查询候选只表达字段选择、聚合和等值筛选。 */
public record ExploratoryQuerySelection(
        List<Aggregate> aggregates,
        List<String> dimensionIds,
        List<Filter> filters,
        TimeGrouping timeGrouping) {

    public ExploratoryQuerySelection(List<Aggregate> aggregates, List<String> dimensionIds, List<Filter> filters) {
        this(aggregates, dimensionIds, filters, null);
    }

    public record TimeGrouping(String fieldId, String grain) {
    }

    public record Aggregate(String fieldId, String operator) {
    }

    public record Filter(String fieldId, Object value) {
    }
}
