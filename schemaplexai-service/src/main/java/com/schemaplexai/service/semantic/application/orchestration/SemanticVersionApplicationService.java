package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.port.SemanticGraphLocator;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
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
    private final SemanticGraphLocator graphLocator;

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion createDraft(String modelId) {
        String tenantId = requireTenantId();
        requireModel(tenantId, modelId);
        int versionNo = versionRepository.nextVersionNo(tenantId, modelId);
        SemanticVersion version = SemanticVersion.createDraft(
                UUID.randomUUID().toString(),
                tenantId,
                modelId,
                versionNo,
                graphLocator.assertedGraph(tenantId, modelId, versionNo));
        try {
            versionRepository.insert(version);
        } catch (DuplicateKeyException exception) {
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

    private void requireModel(String tenantId, String modelId) {
        if (modelRepository.findByTenantAndId(tenantId, modelId).isEmpty()) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND);
        }
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }
}
