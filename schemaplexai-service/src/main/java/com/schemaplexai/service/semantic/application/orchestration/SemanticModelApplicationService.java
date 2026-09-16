package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.CreateSemanticModelCommand;
import com.schemaplexai.service.semantic.application.command.UpdateSemanticModelCommand;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 语义模型控制面 CRUD 编排。 */
@Service
@RequiredArgsConstructor
public class SemanticModelApplicationService {

    private final SemanticModelRepository modelRepository;

    @Transactional(rollbackFor = Exception.class)
    public SemanticModel create(CreateSemanticModelCommand command) {
        String tenantId = requireTenantId();
        SemanticModel model = SemanticModel.create(
                UUID.randomUUID().toString(),
                tenantId,
                command.name(),
                command.domain(),
                command.description());
        requireUniqueName(tenantId, model.getName(), null);
        try {
            modelRepository.insert(model);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_NAME_DUPLICATE);
        }
        return model;
    }

    public List<SemanticModel> list() {
        return modelRepository.findAllByTenant(requireTenantId());
    }

    public SemanticModel get(String modelId) {
        return requireModel(requireTenantId(), modelId);
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticModel update(String modelId, UpdateSemanticModelCommand command) {
        String tenantId = requireTenantId();
        SemanticModel model = requireModel(tenantId, modelId);
        try {
            model.updateProfile(
                    command.name(),
                    command.domain(),
                    command.description(),
                    command.expectedRevision());
        } catch (IllegalStateException exception) {
            throw translateStateException(exception);
        }
        requireUniqueName(tenantId, model.getName(), modelId);
        if (!modelRepository.update(model, command.expectedRevision())) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return model;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(String modelId, long expectedRevision) {
        String tenantId = requireTenantId();
        SemanticModel model = requireModel(tenantId, modelId);
        if (model.getActiveVersionId() != null) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_HAS_ACTIVE_VERSION);
        }
        try {
            model.archive(expectedRevision);
        } catch (IllegalStateException exception) {
            throw translateStateException(exception);
        }
        if (!modelRepository.softDelete(tenantId, modelId, expectedRevision)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    private SemanticModel requireModel(String tenantId, String modelId) {
        return modelRepository.findByTenantAndId(tenantId, modelId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
    }

    private void requireUniqueName(String tenantId, String name, String excludedModelId) {
        if (modelRepository.existsActiveName(tenantId, name, excludedModelId)) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_NAME_DUPLICATE);
        }
    }

    private BusinessException translateStateException(IllegalStateException exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("revision conflict")) {
            return new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return new BusinessException(ResultCode.SEMANTIC_MODEL_STATUS_INVALID, exception.getMessage());
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }
}
