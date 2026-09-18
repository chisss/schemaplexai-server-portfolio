package com.schemaplexai.model.vo.semantic;

import java.util.List;

/** 自然语言语义解释结果。 */
public record SemanticQueryInterpretVO(
        String status,
        String question,
        String semanticVersionId,
        String sourceId,
        QueryIntentVO intent,
        List<ClarificationVO> clarifications,
        List<String> reasons) {

    public record QueryIntentVO(
            String semanticVersionId,
            String sourceId,
            List<MetricVO> metrics,
            List<DimensionVO> dimensions,
            List<FilterVO> filters,
            TimeRangeVO timeRange,
            int limit,
            OutputVO output) {
    }

    public record MetricVO(String iri, String alias, String aggregation, String physicalObject, String physicalField) {
    }

    public record DimensionVO(String iri, String alias, String timeGrain, String physicalObject, String physicalField) {
    }

    public record FilterVO(String iri, String alias, String operator, String value, String physicalObject, String physicalField) {
    }

    public record TimeRangeVO(String fieldIri, Integer relativeDays, String from, String to) {
    }

    public record OutputVO(String view, String reason) {
    }

    public record ClarificationVO(String code, String question, List<String> options, List<String> candidateIris) {
    }
}
