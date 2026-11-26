package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 草稿查询口径，物理来源和列由已有属性映射决定。 */
public record OntologyQueryProfile(
        String classId,
        String timeZone,
        List<Measure> measures,
        List<Dimension> dimensions,
        List<Filter> filters,
        Time time) {

    public record Measure(String propertyId, String aggregation) {
    }

    public record Dimension(String propertyId) {
    }

    public record Filter(String propertyId, List<Value> values) {
    }

    public record Value(String label, String value) {
    }

    public record Time(String propertyId, List<String> grains) {
    }
}
