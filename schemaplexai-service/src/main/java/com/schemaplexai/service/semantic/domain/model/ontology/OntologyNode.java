package com.schemaplexai.service.semantic.domain.model.ontology;

/** 邻域结果中的资源节点。 */
public record OntologyNode(String iri, String label) {

    public OntologyNode {
        iri = java.util.Objects.requireNonNull(iri, "iri is required");
        label = label == null || label.isBlank() ? iri : label.trim();
    }
}
