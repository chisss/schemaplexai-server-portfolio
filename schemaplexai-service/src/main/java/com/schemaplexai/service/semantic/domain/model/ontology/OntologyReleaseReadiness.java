package com.schemaplexai.service.semantic.domain.model.ontology;

/** 按使用目的暴露发布能力。 */
public record OntologyReleaseReadiness(boolean contextReady, boolean validationReady, boolean queryReady) {

    public static OntologyReleaseReadiness structured(boolean published) {
        return structured(published, false);
    }

    public static OntologyReleaseReadiness structured(boolean published, boolean queryProfilePresent) {
        return new OntologyReleaseReadiness(published, false, published && queryProfilePresent);
    }
}
