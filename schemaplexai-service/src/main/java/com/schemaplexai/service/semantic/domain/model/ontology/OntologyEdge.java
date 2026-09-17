package com.schemaplexai.service.semantic.domain.model.ontology;

/** 邻域结果中的关系边。 */
public record OntologyEdge(String subjectIri, String predicateIri, OntologyTerm object) {

    public OntologyEdge {
        subjectIri = java.util.Objects.requireNonNull(subjectIri, "subjectIri is required");
        predicateIri = java.util.Objects.requireNonNull(predicateIri, "predicateIri is required");
        object = java.util.Objects.requireNonNull(object, "object is required");
    }
}
