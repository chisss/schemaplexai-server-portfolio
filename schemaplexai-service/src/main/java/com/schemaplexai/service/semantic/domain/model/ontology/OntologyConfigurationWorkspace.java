package com.schemaplexai.service.semantic.domain.model.ontology;

/** 指定版本的配置视图，既有独立图可以没有结构化配置。 */
public record OntologyConfigurationWorkspace(
        long revision,
        boolean editable,
        OntologyConfiguration configuration,
        String sourceId,
        String sourceSnapshotId,
        OntologyReleaseReadiness readiness) {
}
