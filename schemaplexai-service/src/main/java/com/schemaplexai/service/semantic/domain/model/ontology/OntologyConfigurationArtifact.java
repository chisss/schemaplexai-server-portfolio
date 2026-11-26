package com.schemaplexai.service.semantic.domain.model.ontology;

/** 以语义版本为所有权边界的结构化产物。 */
public record OntologyConfigurationArtifact(
        long revision,
        OntologyConfiguration configuration,
        String ontologyTurtle,
        String shapesTurtle,
        String inferredTurtle,
        String publishedChecksum,
        PublishedQueryProfile queryProfile) {

    public OntologyConfigurationArtifact(long revision, OntologyConfiguration configuration, String ontologyTurtle,
                                         String shapesTurtle, String inferredTurtle, String publishedChecksum) {
        this(revision, configuration, ontologyTurtle, shapesTurtle, inferredTurtle, publishedChecksum, null);
    }

    public boolean published() {
        return publishedChecksum != null;
    }
}
