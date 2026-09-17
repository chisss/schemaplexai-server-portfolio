package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 待写入一个租户语义版本 asserted 图的结构化内容。 */
public record OntologyGraph(OntologyGraphRef ref, List<OntologyStatement> statements) {

    public OntologyGraph {
        ref = java.util.Objects.requireNonNull(ref, "ref is required");
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
