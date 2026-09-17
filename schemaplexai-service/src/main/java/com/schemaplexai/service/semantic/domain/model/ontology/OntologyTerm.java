package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 本体 statement 的对象项，支持资源或字面量。 */
public record OntologyTerm(String value, Kind kind, String datatype, String language) {

    public OntologyTerm {
        kind = Objects.requireNonNull(kind, "kind is required");
        value = normalizeValue(value, kind);
        datatype = normalizeIri(datatype, "datatype");
        language = normalize(language);
        if (kind == Kind.IRI && (datatype != null || language != null)) {
            throw new IllegalArgumentException("IRI term cannot have datatype or language");
        }
        if (kind == Kind.LITERAL && datatype != null && language != null) {
            throw new IllegalArgumentException("literal cannot have datatype and language together");
        }
    }

    public static OntologyTerm iri(String value) {
        return new OntologyTerm(value, Kind.IRI, null, null);
    }

    public static OntologyTerm literal(String value) {
        return new OntologyTerm(value, Kind.LITERAL, null, null);
    }

    public static OntologyTerm typedLiteral(String value, String datatype) {
        return new OntologyTerm(value, Kind.LITERAL, datatype, null);
    }

    public static OntologyTerm languageLiteral(String value, String language) {
        return new OntologyTerm(value, Kind.LITERAL, null, language);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalizeValue(String value, Kind kind) {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        return kind == Kind.IRI ? requireText(value, "value") : value;
    }

    private static String normalizeIri(String value, String field) {
        String normalized = normalize(value);
        if (normalized != null && !(normalized.startsWith("urn:")
                || normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            throw new IllegalArgumentException(field + " must be an absolute IRI");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public enum Kind {
        IRI,
        LITERAL
    }
}
