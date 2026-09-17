package com.schemaplexai.service.semantic.application.command;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;

import java.util.List;

/** 发布语义版本命令。 */
public record PublishSemanticVersionCommand(
        long expectedVersionRevision,
        long expectedModelRevision,
        List<OntologyStatement> shapes) {

    public PublishSemanticVersionCommand {
        if (expectedVersionRevision < 0 || expectedModelRevision < 0) {
            throw new IllegalArgumentException("expected revision must not be negative");
        }
        shapes = shapes == null ? List.of() : List.copyOf(shapes);
    }
}
