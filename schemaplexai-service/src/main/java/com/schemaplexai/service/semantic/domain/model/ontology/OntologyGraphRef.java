package com.schemaplexai.service.semantic.domain.model.ontology;

import java.util.Objects;

/** 租户语义版本的服务端图引用。 */
public record OntologyGraphRef(String tenantId, String modelId, long version) {

    public OntologyGraphRef {
        tenantId = requireText(tenantId, "tenantId");
        modelId = requireText(modelId, "modelId");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
