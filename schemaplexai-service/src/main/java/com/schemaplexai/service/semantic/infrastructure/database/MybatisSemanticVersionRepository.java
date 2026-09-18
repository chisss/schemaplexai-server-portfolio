package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticVersionMapper;
import com.schemaplexai.model.entity.semantic.SemanticVersionEntity;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** MyBatis 语义版本仓储适配器。 */
@Repository
@RequiredArgsConstructor
public class MybatisSemanticVersionRepository implements SemanticVersionRepository {

    private final SemanticVersionMapper mapper;

    @Override
    public void insert(SemanticVersion version) {
        mapper.insert(toEntity(version));
    }

    @Override
    public boolean update(SemanticVersion version, long expectedRevision) {
        return mapper.updateOwnedWithRevision(toEntity(version), expectedRevision) == 1;
    }

    @Override
    public Optional<SemanticVersion> findByTenantModelAndId(
            String tenantId,
            String modelId,
            String versionId) {
        return Optional.ofNullable(mapper.selectOwned(tenantId, modelId, versionId)).map(this::toDomain);
    }

    @Override
    public Optional<SemanticVersion> findByTenantAndId(String tenantId, String versionId) {
        return Optional.ofNullable(mapper.selectOwnedById(tenantId, versionId)).map(this::toDomain);
    }

    @Override
    public List<SemanticVersion> findAllByTenantAndModel(String tenantId, String modelId) {
        return mapper.selectAllOwned(tenantId, modelId).stream().map(this::toDomain).toList();
    }

    @Override
    public int nextVersionNo(String tenantId, String modelId) {
        return mapper.selectMaxVersionNo(tenantId, modelId) + 1;
    }

    private SemanticVersionEntity toEntity(SemanticVersion version) {
        SemanticVersionEntity entity = new SemanticVersionEntity();
        entity.setId(version.getId());
        entity.setTenantId(version.getTenantId());
        entity.setModelId(version.getModelId());
        entity.setVersionNo(version.getVersionNo());
        entity.setGraphIri(version.getGraphIri());
        entity.setSourceSnapshotId(version.getSourceSnapshotId());
        entity.setStatus(version.getStatus().name().toLowerCase(Locale.ROOT));
        entity.setChecksum(version.getChecksum());
        entity.setTripleCount(version.getTripleCount());
        entity.setValidationReport(version.getValidationReport());
        entity.setPublishedAt(version.getPublishedAt());
        entity.setRevision(version.getRevision());
        entity.setDeleted(0);
        return entity;
    }

    private SemanticVersion toDomain(SemanticVersionEntity entity) {
        return SemanticVersion.restore(
                entity.getId(),
                entity.getTenantId(),
                entity.getModelId(),
                entity.getVersionNo(),
                entity.getGraphIri(),
                entity.getSourceSnapshotId(),
                SemanticVersionStatus.valueOf(entity.getStatus().toUpperCase(Locale.ROOT)),
                entity.getChecksum(),
                entity.getTripleCount(),
                entity.getValidationReport(),
                entity.getPublishedAt(),
                entity.getRevision());
    }
}
