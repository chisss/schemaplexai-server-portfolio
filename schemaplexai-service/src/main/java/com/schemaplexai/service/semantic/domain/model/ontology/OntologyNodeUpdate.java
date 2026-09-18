package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 本体节点可编辑元数据，不允许携带原始 RDF 或可执行表达式。 */
public record OntologyNodeUpdate(
        String name,
        String label,
        String description,
        List<String> synonyms,
        String dataType,
        Boolean required,
        PhysicalMapping mapping) {

    public OntologyNodeUpdate {
        name = requireText(name, "name");
        label = normalize(label);
        description = normalize(description);
        synonyms = synonyms == null ? List.of() : synonyms.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        dataType = normalize(dataType);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 语义节点到企业数据源物理对象的受控映射。 */
    public record PhysicalMapping(
            String sourceId,
            String physicalObject,
            String physicalField,
            String mappingKind) {

        public PhysicalMapping {
            sourceId = requireText(sourceId, "sourceId");
            physicalObject = requireText(physicalObject, "physicalObject");
            physicalField = normalize(physicalField);
            mappingKind = requireText(mappingKind, "mappingKind").toLowerCase();
            if (!List.of("table", "column", "collection", "field").contains(mappingKind)) {
                throw new IllegalArgumentException("unsupported mappingKind");
            }
        }
    }
}
