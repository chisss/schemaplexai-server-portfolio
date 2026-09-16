package com.schemaplexai.service.semantic.domain.repository;

import com.schemaplexai.service.semantic.domain.model.SemanticVersion;

import java.util.List;
import java.util.Optional;

/** 语义版本仓储端口。 */
public interface SemanticVersionRepository {

    void insert(SemanticVersion version);

    boolean update(SemanticVersion version, long expectedRevision);

    Optional<SemanticVersion> findByTenantModelAndId(String tenantId, String modelId, String versionId);

    List<SemanticVersion> findAllByTenantAndModel(String tenantId, String modelId);

    int nextVersionNo(String tenantId, String modelId);
}
