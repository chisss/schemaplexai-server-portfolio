package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.semantic.OntologyConfigurationArtifactMapper;
import com.schemaplexai.dao.mapper.semantic.OntologyConfigurationArtifactMapper.ArtifactRow;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 由 PostgreSQL 保存和恢复结构化本体版本。 */
@Repository
@RequiredArgsConstructor
public class MybatisOntologyConfigurationStore implements OntologyConfigurationStorePort {

    private final OntologyConfigurationArtifactMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<OntologyConfigurationArtifact> find(String tenantId, String versionId) {
        return Optional.ofNullable(mapper.find(tenantId, versionId)).map(this::toArtifact);
    }

    @Override
    public Optional<Boolean> published(String tenantId, String versionId) {
        return Optional.ofNullable(mapper.published(tenantId, versionId));
    }

    @Override
    public boolean queryReady(String tenantId, String versionId) {
        return Boolean.TRUE.equals(mapper.queryReady(tenantId, versionId));
    }

    @Override
    public boolean saveDraft(String tenantId, String versionId, OntologyConfigurationArtifact artifact) {
        try {
            String configurationJson = objectMapper.writeValueAsString(artifact.configuration());
            return mapper.saveDraft(tenantId, versionId, artifact.revision(), configurationJson,
                    artifact.ontologyTurtle(), artifact.shapesTurtle()) == 1;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化本体配置", exception);
        }
    }

    @Override
    public boolean freeze(String tenantId, String versionId, long expectedArtifactRevision, long publishedRevision,
                          String checksum, String inferredTurtle, PublishedQueryProfile queryProfile) {
        try {
            String queryJson = queryProfile == null ? null : objectMapper.writeValueAsString(queryProfile);
            return mapper.freeze(tenantId, versionId, expectedArtifactRevision, publishedRevision, checksum,
                    inferredTurtle, queryJson) == 1;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化本体查询口径", exception);
        }
    }

    private OntologyConfigurationArtifact toArtifact(ArtifactRow row) {
        try {
            var configuration = objectMapper.readValue(row.configurationJson(), OntologyConfiguration.class);
            var queryProfile = row.queryProfileJson() == null ? null
                    : objectMapper.readValue(row.queryProfileJson(), PublishedQueryProfile.class);
            return new OntologyConfigurationArtifact(row.revision(), configuration, row.ontologyTurtle(),
                    row.shapesTurtle(), row.inferredTurtle(), row.publishedChecksum(), queryProfile);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取本体配置", exception);
        }
    }
}
