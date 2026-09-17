package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 结构化本体三元组，不携带 Jena 类型。 */
public record OntologyStatement(String subjectIri, String predicateIri, OntologyTerm object) {

    public OntologyStatement {
        subjectIri = requireIri(subjectIri, "subjectIri");
        predicateIri = requireIri(predicateIri, "predicateIri");
        object = Objects.requireNonNull(object, "object is required");
        if (object.kind() == OntologyTerm.Kind.IRI) {
            requireIri(object.value(), "object.value");
        }
    }

    private static String requireIri(String value, String field) {
        if (value == null || value.isBlank() || !(value.startsWith("urn:")
                || value.startsWith("http://") || value.startsWith("https://"))) {
            throw new IllegalArgumentException(field + " must be an absolute IRI");
        }
        return value.trim();
    }
}
