package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.SemanticGraphLocator;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.BaselineSemanticShapes;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 语义版本控制面 CRUD 编排。 */
@Service
@RequiredArgsConstructor
public class SemanticVersionApplicationService {

    private final SemanticModelRepository modelRepository;
    private final SemanticVersionRepository versionRepository;
    private final SchemaSnapshotRepository snapshotRepository;
    private final SemanticGraphLocator graphLocator;
    private final SchemaScanApplicationService schemaScanService;
    private final OntologyConfigurationStorePort configurationStore;
    private final OntologyArtifactCompilerPort artifactCompiler;

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion createDraft(String modelId) {
        return createDraft(modelId, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion createDraft(String modelId, String sourceSnapshotId) {
        String tenantId = requireTenantId();
        String normalizedSnapshotId = normalizeSnapshotId(tenantId, sourceSnapshotId);
        // 在独立 SQL 语句取得模型行锁后再读版本号，确保等待期间提交的版本可见。
        SemanticModel model = modelRepository.lockByTenantAndId(tenantId, modelId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
        if (model.getStatus() != SemanticModelStatus.ACTIVE) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_STATUS_INVALID);
        }
        int versionNo = versionRepository.nextVersionNo(tenantId, modelId);
        SemanticVersion version = SemanticVersion.createDraft(
                UUID.randomUUID().toString(),
                tenantId,
                modelId,
                versionNo,
                graphLocator.assertedGraph(tenantId, modelId, versionNo),
                normalizedSnapshotId);
        try {
            versionRepository.insert(version);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return version;
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion createConfigurationDraft(String modelId, String sourceSnapshotId) {
        SemanticVersion version = createDraft(modelId, sourceSnapshotId);
        var initialArtifact = new OntologyConfigurationArtifact(0, OntologyConfiguration.empty(), "",
                artifactCompiler.turtle(new BaselineSemanticShapes().statements()), null, null);
        if (!configurationStore.saveDraft(version.getTenantId(), version.getId(), initialArtifact)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return version;
    }

    public List<SemanticVersion> list(String modelId) {
        String tenantId = requireTenantId();
        requireModel(tenantId, modelId);
        return versionRepository.findAllByTenantAndModel(tenantId, modelId);
    }

    public SemanticVersion get(String modelId, String versionId) {
        String tenantId = requireTenantId();
        requireModel(tenantId, modelId);
        return versionRepository.findByTenantModelAndId(tenantId, modelId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    public SemanticVersion get(String versionId) {
        return versionRepository.findByTenantAndId(requireTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    public SemanticVersion lock(String versionId) {
        return versionRepository.lockByTenantAndId(requireTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    private SemanticModel requireModel(String tenantId, String modelId) {
        return modelRepository.findByTenantAndId(tenantId, modelId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
    }

    private String normalizeSnapshotId(String tenantId, String sourceSnapshotId) {
        if (sourceSnapshotId == null || sourceSnapshotId.isBlank()) {
            return null;
        }
        String snapshotId;
        try {
            snapshotId = UUID.fromString(sourceSnapshotId.trim()).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.SCHEMA_SNAPSHOT_NOT_FOUND);
        }
        SchemaSnapshot snapshot = snapshotRepository.findByTenantAndId(tenantId, snapshotId)
                .orElseThrow(() -> new BusinessException(ResultCode.SCHEMA_SNAPSHOT_NOT_FOUND));
        schemaScanService.getSnapshot(snapshot.getSourceId(), snapshotId);
        return snapshotId;
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }
}
