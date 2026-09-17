package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 邻域结果中的资源节点。 */
public record OntologyNode(String iri, String label) {

    public OntologyNode {
        iri = Objects.requireNonNull(iri, "iri is required");
        label = label == null || label.isBlank() ? iri : label.trim();
    }
}
