package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.enums.OntologyReleasePurpose;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.PublishSemanticVersionCommand;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyReleaseReadiness;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;
import com.schemaplexai.service.semantic.domain.model.ontology.SemanticValidation;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort.ReleaseCheck;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.SemanticPublishPort;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.BaselineSemanticShapes;
import com.schemaplexai.service.semantic.domain.service.OntologyQueryProfileCompiler;
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
    private final OntologyConfigurationStorePort configurationStore;
    private final OntologyArtifactCompilerPort artifactCompiler;
    private final OntologyConfigurationApplicationService configurations;
    private final BaselineSemanticShapes baselineShapes = new BaselineSemanticShapes();
    private final OntologyQueryProfileCompiler queryCompiler = new OntologyQueryProfileCompiler();

    @Transactional(rollbackFor = Exception.class)
    public SemanticValidation validate(String versionId, long expectedRevision) {
        return validate(versionId, expectedRevision, OntologyReleasePurpose.CONTEXT);
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticValidation validate(String versionId, long expectedRevision, OntologyReleasePurpose purpose) {
        SemanticVersion version = versionRepository.lockByTenantAndId(requireTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        if (!version.isEditable()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID);
        }
        if (version.getRevision() != expectedRevision) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        OntologyConfigurationArtifact artifact = findArtifact(version);
        if (artifact != null) {
            var check = checkStructured(version, artifact, purpose).release();
            return new SemanticValidation(check.valid(), check.report(), LocalDateTime.now());
        }
        if (purpose == OntologyReleasePurpose.QUERY) {
            return new SemanticValidation(false, "请先建立带查询口径的结构化本体草稿", LocalDateTime.now());
        }
        PublishPreparation preparation = publishPort.prepare(
                new OntologyGraphRef(
                        version.getTenantId(),
                        version.getModelId(),
                        version.getVersionNo()),
                baselineShapes.statements(),
                UUID.randomUUID().toString());
        try {
            return new SemanticValidation(
                    preparation.conforms(),
                    preparation.validationReport(),
                    LocalDateTime.now());
        } finally {
            publishPort.discard(preparation);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion publish(String versionId, long expectedRevision) {
        return publish(versionId, expectedRevision, OntologyReleasePurpose.CONTEXT);
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion publish(String versionId, long expectedRevision, OntologyReleasePurpose purpose) {
        SemanticVersion version = requireVersion(versionId);
        SemanticModel model = modelRepository.findByTenantAndId(version.getTenantId(), version.getModelId())
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
        return publish(
                model.getId(),
                version.getId(),
                new PublishSemanticVersionCommand(
                        expectedRevision,
                        model.getRevision(),
                        baselineShapes.statements(), purpose));
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion publish(
            String modelId,
            String versionId,
            PublishSemanticVersionCommand command) {
        String tenantId = requireTenantId();
        SemanticModel model = modelRepository.findByTenantAndId(tenantId, modelId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
        if (model.getStatus() != SemanticModelStatus.ACTIVE) {
            throw new BusinessException(ResultCode.SEMANTIC_MODEL_STATUS_INVALID);
        }
        SemanticVersion version = versionRepository.findByTenantModelAndId(tenantId, modelId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        startValidation(version, command.expectedVersionRevision());

        OntologyConfigurationArtifact artifact = findArtifact(version);
        if (artifact != null) {
            return publishStructured(model, version, command, artifact);
        }
        if (command.purpose() == OntologyReleasePurpose.QUERY) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "历史本体需要建立带查询口径的结构化版本");
        }
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

    private SemanticVersion publishStructured(SemanticModel model, SemanticVersion version,
                                             PublishSemanticVersionCommand command, OntologyConfigurationArtifact artifact) {
        var candidate = checkStructured(version, artifact, command.purpose());
        var check = candidate.release();
        long validatingRevision = command.expectedVersionRevision() + 1;
        if (!check.valid()) {
            markInvalid(version, check.report(), validatingRevision);
            return version;
        }
        completePublication(model, version, check.checksum(), check.tripleCount(), command);
        if (!configurationStore.freeze(version.getTenantId(), version.getId(), artifact.revision(),
                version.getRevision(), check.checksum(), check.inferredTurtle(), candidate.queryProfile())) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return version;
    }

    private StructuredRelease checkStructured(SemanticVersion version, OntologyConfigurationArtifact artifact,
                                              OntologyReleasePurpose purpose) {
        String snapshotId = version.getSourceSnapshotId();
        if (purpose != OntologyReleasePurpose.QUERY) {
            return new StructuredRelease(artifactCompiler.validate(artifact, snapshotId, null), null);
        }
        var snapshot = configurations.authorizedSnapshot(version);
        try {
            var ref = new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo());
            var profile = queryCompiler.compile(ref, artifact.configuration(), snapshot);
            return new StructuredRelease(artifactCompiler.validate(artifact, snapshotId, profile), profile);
        } catch (IllegalArgumentException exception) {
            return new StructuredRelease(new ReleaseCheck(false, exception.getMessage(), null, 0, null), null);
        }
    }

    public OntologyReleaseReadiness releaseReadiness(SemanticVersion version) {
        return configurationStore.published(version.getTenantId(), version.getId())
                .map(published -> OntologyReleaseReadiness.structured(published,
                        published && configurationStore.queryReady(version.getTenantId(), version.getId()))).orElse(null);
    }

    private OntologyConfigurationArtifact findArtifact(SemanticVersion version) {
        return configurationStore.find(version.getTenantId(), version.getId()).orElse(null);
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
        markInvalid(version, preparation.validationReport(), validatingRevision);
    }

    private void markInvalid(SemanticVersion version, String report, long validatingRevision) {
        version.markInvalid(report, validatingRevision);
        if (!versionRepository.update(version, validatingRevision)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    private void completePublication(
            SemanticModel model,
            SemanticVersion version,
            PublishPreparation preparation,
            PublishSemanticVersionCommand command) {
        completePublication(model, version, preparation.checksum(), preparation.tripleCount(), command);
    }

    private void completePublication(
            SemanticModel model, SemanticVersion version, String checksum, long tripleCount,
            PublishSemanticVersionCommand command) {
        long validatingRevision = command.expectedVersionRevision() + 1;
        try {
            version.publish(
                    checksum,
                    tripleCount,
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

    private SemanticVersion requireVersion(String versionId) {
        return versionRepository.findByTenantAndId(requireTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    private record StructuredRelease(ReleaseCheck release, PublishedQueryProfile queryProfile) {
    }
}
