package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.PublishSemanticVersionCommand;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;
import com.schemaplexai.service.semantic.domain.port.SemanticPublishPort;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/** 编排语义版本校验、物化和控制面激活。 */
@Service
@RequiredArgsConstructor
public class SemanticPublishApplicationService {

    private final SemanticModelRepository modelRepository;
    private final SemanticVersionRepository versionRepository;
    private final SemanticPublishPort publishPort;

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion publish(
            String modelId,
            String versionId,
            PublishSemanticVersionCommand command) {
        String tenantId = requireTenantId();
        SemanticModel model = modelRepository.findByTenantAndId(tenantId, modelId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
        SemanticVersion version = versionRepository.findByTenantModelAndId(tenantId, modelId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        startValidation(version, command.expectedVersionRevision());

        PublishPreparation preparation = publishPort.prepare(
                new OntologyGraphRef(tenantId, modelId, version.getVersionNo()),
                command.shapes(),
                UUID.randomUUID().toString());
        if (!preparation.conforms()) {
            try {
                markInvalid(version, preparation, command.expectedVersionRevision() + 1);
                return version;
            } finally {
                publishPort.discard(preparation);
            }
        }

        try {
            completePublication(model, version, preparation, command);
            publishPort.commit(preparation);
            return version;
        } catch (RuntimeException exception) {
            publishPort.discard(preparation);
            throw exception;
        }
    }

    private void startValidation(SemanticVersion version, long expectedRevision) {
        try {
            version.startValidation(expectedRevision);
        } catch (IllegalStateException exception) {
            throw translateVersionState(exception);
        }
        if (!versionRepository.update(version, expectedRevision)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    private void markInvalid(
            SemanticVersion version,
            PublishPreparation preparation,
            long validatingRevision) {
        version.markInvalid(preparation.validationReport(), validatingRevision);
        if (!versionRepository.update(version, validatingRevision)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    private void completePublication(
            SemanticModel model,
            SemanticVersion version,
            PublishPreparation preparation,
            PublishSemanticVersionCommand command) {
        long validatingRevision = command.expectedVersionRevision() + 1;
        try {
            version.publish(
                    preparation.checksum(),
                    preparation.tripleCount(),
                    LocalDateTime.now(),
                    validatingRevision);
        } catch (IllegalStateException exception) {
            throw translateVersionState(exception);
        }
        try {
            model.activateVersion(version.getId(), command.expectedModelRevision());
        } catch (IllegalStateException exception) {
            throw translateModelState(exception);
        }
        if (!versionRepository.update(version, validatingRevision)
                || !modelRepository.update(model, command.expectedModelRevision())) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    private BusinessException translateVersionState(IllegalStateException exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("revision conflict")) {
            return new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, exception.getMessage());
    }

    private BusinessException translateModelState(IllegalStateException exception) {
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
