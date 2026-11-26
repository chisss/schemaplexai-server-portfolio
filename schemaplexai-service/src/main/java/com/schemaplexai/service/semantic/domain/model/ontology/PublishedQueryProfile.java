package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 发布时从版本平面和 Schema 快照核验的单源查询口径。 */
public record PublishedQueryProfile(
        String sourceId,
        String sourceSnapshotId,
        String sourceFingerprint,
        String databaseType,
        String physicalObject,
        String timeZone,
        List<Measure> measures,
        List<Dimension> dimensions,
        List<Filter> filters,
        Time time) {

    public record Measure(String propertyId, String iri, String name, String label, String physicalField,
                          String dataType, String physicalType, String aggregation) {

        public String resultKey() {
            return propertyId + "_" + aggregation;
        }
    }

    public record Dimension(String propertyId, String iri, String name, String label, String physicalField,
                            String dataType, String physicalType) {

        public String resultKey() {
            return propertyId;
        }
    }

    public record Filter(String propertyId, String iri, String name, String label, String physicalField,
                         String dataType, String physicalType, List<Value> values) {
    }

    public record Value(String label, String value) {
    }

    public record Time(String propertyId, String iri, String name, String label, String physicalField,
                       String dataType, String physicalType, List<String> grains) {
    }
}
