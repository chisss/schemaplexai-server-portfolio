package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 本体版本发布前的临时图校验结果。 */
public record PublishPreparation(
        OntologyGraphRef ref,
        String operationId,
        boolean conforms,
        String validationReport,
        String checksum,
        long tripleCount) {

    public PublishPreparation {
        ref = Objects.requireNonNull(ref, "ref is required");
        operationId = requireText(operationId, "operationId");
        validationReport = normalize(validationReport);
        checksum = normalize(checksum);
        if (conforms && checksum == null) {
            throw new IllegalArgumentException("conforming preparation requires checksum");
        }
        if (!conforms && validationReport == null) {
            throw new IllegalArgumentException("invalid preparation requires validation report");
        }
        if (tripleCount < 0) {
            throw new IllegalArgumentException("tripleCount must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
