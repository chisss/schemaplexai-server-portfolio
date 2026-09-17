package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 邻域结果中的关系边。 */
public record OntologyEdge(String subjectIri, String predicateIri, OntologyTerm object) {

    public OntologyEdge {
        subjectIri = Objects.requireNonNull(subjectIri, "subjectIri is required");
        predicateIri = Objects.requireNonNull(predicateIri, "predicateIri is required");
        object = Objects.requireNonNull(object, "object is required");
    }
}
