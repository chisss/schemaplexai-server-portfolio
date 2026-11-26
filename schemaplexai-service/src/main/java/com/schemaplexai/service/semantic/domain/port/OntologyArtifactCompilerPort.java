package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;

import java.util.List;

/** RDF 序列化和内存校验。 */
public interface OntologyArtifactCompilerPort {

    String turtle(List<OntologyStatement> statements);

    OntologySubgraph query(OntologyGraphRef ref, String ontologyTurtle, GraphQuery query, boolean includeMappings);

    ReleaseCheck validate(OntologyConfigurationArtifact artifact, String sourceSnapshotId, PublishedQueryProfile queryProfile);

    record ReleaseCheck(boolean valid, String report, String checksum, long tripleCount, String inferredTurtle) {
    }
}
