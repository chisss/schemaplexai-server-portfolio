package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 工作台可维护的概念、属性和关系。 */
public record OntologyConfiguration(
        List<Concept> classes,
        List<Property> properties,
        List<Relationship> relationships,
        OntologyQueryProfile queryProfile) {

    public OntologyConfiguration(List<Concept> classes, List<Property> properties, List<Relationship> relationships) {
        this(classes, properties, relationships, null);
    }

    public static OntologyConfiguration empty() {
        return new OntologyConfiguration(List.of(), List.of(), List.of());
    }

    public record Concept(String id, String name, String label, String description, String sourceObject) {
    }

    public record Property(
            String id, String classId, String name, String label, String dataType, boolean required, String sourceField) {
    }

    public record Relationship(
            String id, String fromClassId, String toClassId, String name, String label, String kind) {
    }
}
