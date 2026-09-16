package com.schemaplexai.service.semantic.domain.repository;

import com.schemaplexai.service.semantic.domain.model.SemanticModel;

import java.util.List;
import java.util.Optional;

/** 语义模型仓储端口。 */
public interface SemanticModelRepository {

    void insert(SemanticModel model);

    boolean update(SemanticModel model, long expectedRevision);

    boolean softDelete(String tenantId, String modelId, long expectedRevision);

    Optional<SemanticModel> findByTenantAndId(String tenantId, String modelId);

    List<SemanticModel> findAllByTenant(String tenantId);

    boolean existsActiveName(String tenantId, String name, String excludedModelId);
}
