package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;

/** 需要用户补充的信息。 */
public record SemanticQueryClarification(
        String code,
        String question,
        List<String> options,
        List<String> candidateIris) {

    public SemanticQueryClarification {
        options = options == null ? List.of() : List.copyOf(options);
        candidateIris = candidateIris == null ? List.of() : List.copyOf(candidateIris);
    }
}
