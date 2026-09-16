package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticModelMapper;
import com.schemaplexai.model.entity.semantic.SemanticModelEntity;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** MyBatis 语义模型仓储适配器。 */
@Repository
@RequiredArgsConstructor
public class MybatisSemanticModelRepository implements SemanticModelRepository {

    private final SemanticModelMapper mapper;

    @Override
    public void insert(SemanticModel model) {
        mapper.insert(toEntity(model));
    }

    @Override
    public boolean update(SemanticModel model, long expectedRevision) {
        return mapper.updateOwnedWithRevision(toEntity(model), expectedRevision) == 1;
    }

    @Override
    public boolean softDelete(String tenantId, String modelId, long expectedRevision) {
        return mapper.softDeleteOwned(tenantId, modelId, expectedRevision) == 1;
    }

    @Override
    public Optional<SemanticModel> findByTenantAndId(String tenantId, String modelId) {
        return Optional.ofNullable(mapper.selectOwned(tenantId, modelId)).map(this::toDomain);
    }

    @Override
    public List<SemanticModel> findAllByTenant(String tenantId) {
        return mapper.selectAllOwned(tenantId).stream().map(this::toDomain).toList();
    }

    @Override
    public boolean existsActiveName(String tenantId, String name, String excludedModelId) {
        return mapper.countOwnedName(tenantId, name, excludedModelId) > 0;
    }

    private SemanticModelEntity toEntity(SemanticModel model) {
        SemanticModelEntity entity = new SemanticModelEntity();
        entity.setId(model.getId());
        entity.setTenantId(model.getTenantId());
        entity.setName(model.getName());
        entity.setDomain(model.getDomain());
        entity.setDescription(model.getDescription());
        entity.setActiveVersionId(model.getActiveVersionId());
        entity.setStatus(model.getStatus().name().toLowerCase(Locale.ROOT));
        entity.setRevision(model.getRevision());
        entity.setDeleted(0);
        return entity;
    }

    private SemanticModel toDomain(SemanticModelEntity entity) {
        return SemanticModel.restore(
                entity.getId(),
                entity.getTenantId(),
                entity.getName(),
                entity.getDomain(),
                entity.getDescription(),
                entity.getActiveVersionId(),
                SemanticModelStatus.valueOf(entity.getStatus().toUpperCase(Locale.ROOT)),
                entity.getRevision());
    }
}
