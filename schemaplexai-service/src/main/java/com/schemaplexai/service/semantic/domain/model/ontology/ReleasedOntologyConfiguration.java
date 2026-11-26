package com.schemaplexai.service.semantic.domain.model.ontology;

/** 运行时读取的已发布本体与版本校验和。 */
public record ReleasedOntologyConfiguration(
        String tenantId, String modelId, String versionId, long revision,
        String checksum, OntologyConfiguration configuration) {
}
