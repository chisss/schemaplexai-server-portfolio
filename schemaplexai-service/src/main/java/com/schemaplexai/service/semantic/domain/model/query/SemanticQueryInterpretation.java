package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 语义解释结果。 */
public record SemanticQueryInterpretation(
        QueryInterpretationStatus status,
        String question,
        String semanticVersionId,
        String sourceId,
        QueryIntent intent,
        List<SemanticQueryClarification> clarifications,
        List<String> reasons) {

    public SemanticQueryInterpretation {
        status = Objects.requireNonNull(status, "status is required");
        question = Objects.requireNonNull(question, "question is required").trim();
        semanticVersionId = Objects.requireNonNull(semanticVersionId, "semanticVersionId is required").trim();
        sourceId = Objects.requireNonNull(sourceId, "sourceId is required").trim();
        clarifications = clarifications == null ? List.of() : clarifications.stream().limit(3).toList();
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (status == QueryInterpretationStatus.READY && intent == null) {
            throw new IllegalArgumentException("ready interpretation requires intent");
        }
        if (status != QueryInterpretationStatus.READY && intent != null) {
            throw new IllegalArgumentException("non-ready interpretation cannot contain intent");
        }
    }

    public static SemanticQueryInterpretation ready(String question, QueryIntent intent) {
        return new SemanticQueryInterpretation(
                QueryInterpretationStatus.READY,
                question,
                intent.semanticVersionId(),
                intent.sourceId(),
                intent,
                List.of(),
                List.of());
    }

    public static SemanticQueryInterpretation clarification(
            String question,
            String semanticVersionId,
            String sourceId,
            List<SemanticQueryClarification> clarifications,
            List<String> reasons) {
        return new SemanticQueryInterpretation(
                QueryInterpretationStatus.CLARIFICATION,
                question,
                semanticVersionId,
                sourceId,
                null,
                clarifications,
                reasons);
    }

    public static SemanticQueryInterpretation rejected(
            String question,
            String semanticVersionId,
            String sourceId,
            List<String> reasons) {
        return new SemanticQueryInterpretation(
                QueryInterpretationStatus.REJECTED,
                question,
                semanticVersionId,
                sourceId,
                null,
                List.of(),
                reasons);
    }
}
