package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 从已发布本体图投影出的查询候选。 */
public record SemanticQueryCandidate(
        String iri,
        QueryCandidateRole role,
        List<String> aliases,
        String sourceId,
        String physicalObject,
        String physicalField,
        String mappingKind,
        String aggregation) {

    public SemanticQueryCandidate(
            String iri,
            QueryCandidateRole role,
            List<String> aliases,
            String sourceId,
            String physicalObject,
            String physicalField,
            String mappingKind) {
        this(iri, role, aliases, sourceId, physicalObject, physicalField, mappingKind, null);
    }

    public SemanticQueryCandidate {
        iri = Objects.requireNonNull(iri, "iri is required").trim();
        role = Objects.requireNonNull(role, "role is required");
        aliases = aliases == null ? List.of() : aliases.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        sourceId = normalize(sourceId);
        physicalObject = normalize(physicalObject);
        physicalField = normalize(physicalField);
        mappingKind = normalize(mappingKind);
        aggregation = normalize(aggregation);
    }

    public boolean isMappedTo(String requestedSourceId) {
        return sourceId != null && sourceId.equals(requestedSourceId)
                && physicalObject != null && physicalField != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
