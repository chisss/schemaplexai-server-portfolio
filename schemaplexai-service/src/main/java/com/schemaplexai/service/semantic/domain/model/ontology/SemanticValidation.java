package com.schemaplexai.service.semantic.domain.model.ontology;

import java.time.LocalDateTime;
import java.util.Objects;

/** 不改变版本状态的语义图校验结果。 */
public record SemanticValidation(boolean valid, String report, LocalDateTime validatedAt) {

    public SemanticValidation {
        report = report == null || report.isBlank() ? null : report.trim();
        validatedAt = Objects.requireNonNull(validatedAt, "validatedAt is required");
    }
}
