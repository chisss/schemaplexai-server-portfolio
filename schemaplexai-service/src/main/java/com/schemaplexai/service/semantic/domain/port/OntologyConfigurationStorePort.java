package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;

import java.util.Optional;

/** 在语义版本的控制面事务中保存草稿和不可变发布产物。 */
public interface OntologyConfigurationStorePort {

    Optional<OntologyConfigurationArtifact> find(String tenantId, String versionId);

    Optional<Boolean> published(String tenantId, String versionId);

    boolean queryReady(String tenantId, String versionId);

    boolean saveDraft(String tenantId, String versionId, OntologyConfigurationArtifact artifact);

    boolean freeze(String tenantId, String versionId, long expectedArtifactRevision, long publishedRevision,
                   String checksum, String inferredTurtle, PublishedQueryProfile queryProfile);
}
