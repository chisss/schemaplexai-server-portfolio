package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.List;

/** 租户限定、分页后的本体邻域。 */
public record OntologySubgraph(
        OntologyGraphRef ref,
        List<OntologyNode> nodes,
        List<OntologyEdge> edges,
        long totalNodes,
        boolean hasMore) {

    public OntologySubgraph {
        ref = java.util.Objects.requireNonNull(ref, "ref is required");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        if (totalNodes < 0) {
            throw new IllegalArgumentException("totalNodes must not be negative");
        }
    }
}
