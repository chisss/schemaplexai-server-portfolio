package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;
import java.util.Objects;

/** 待写入一个租户语义版本 asserted 图的结构化内容。 */
public record OntologyGraph(OntologyGraphRef ref, List<OntologyStatement> statements) {

    public OntologyGraph {
        ref = Objects.requireNonNull(ref, "ref is required");
        statements = statements == null ? List.of() : List.copyOf(statements);
    }
}
