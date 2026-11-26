package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.common.SemanticModelStatus;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyArtifacts;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationWorkspace;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyReleaseReadiness;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.ReleasedOntologyConfiguration;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.BaselineSemanticShapes;
import com.schemaplexai.service.semantic.domain.service.OntologyConfigurationCompiler;
import com.schemaplexai.service.semantic.domain.service.OntologyConfigurationCompiler.CompiledOntology;
import com.schemaplexai.service.semantic.domain.service.OntologyQueryProfileCompiler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 管理本体表单配置、候选校验和版本产物。 */
@Service
@RequiredArgsConstructor
public class OntologyConfigurationApplicationService {

    private final SemanticVersionApplicationService versionService;
    private final SemanticModelRepository modelRepository;
    private final SemanticVersionRepository versionRepository;
    private final SchemaSnapshotRepository snapshotRepository;
    private final SchemaScanApplicationService schemaScanService;
    private final OntologyConfigurationStorePort configurationStore;
    private final OntologyArtifactCompilerPort artifactCompiler;
    private final OntologyStorePort legacyStore;
    private final AuthorizationService authorization;
    private final OntologyConfigurationCompiler compiler = new OntologyConfigurationCompiler();
    private final OntologyQueryProfileCompiler queryCompiler = new OntologyQueryProfileCompiler();
    private final BaselineSemanticShapes baselineShapes = new BaselineSemanticShapes();

    @Transactional(rollbackFor = Exception.class)
    public OntologyConfigurationWorkspace load(String versionId) {
        SemanticVersion version = versionService.lock(normalizedId(versionId));
        OntologyConfigurationArtifact artifact = readArtifact(version);
        if (!version.isEditable() && version.getSourceSnapshotId() != null) {
            return publishedWorkspace(version, artifact);
        }
        return workspace(version, authorizedSnapshot(version), artifact);
    }

    @Transactional(rollbackFor = Exception.class)
    public OntologyConfigurationWorkspace save(
            String versionId, long expectedRevision, OntologyConfiguration configuration) {
        SemanticVersion version = versionService.lock(normalizedId(versionId));
        requireEditable(version, expectedRevision, true);
        SchemaSnapshot snapshot = authorizedSnapshot(version);
        CompiledOntology compiled = compile(ref(version), configuration, snapshot);
        List<OntologyStatement> shapes = new ArrayList<>(baselineShapes.statements());
        shapes.addAll(compiled.shapes());
        version.recordDraftEdit(expectedRevision);
        var artifact = new OntologyConfigurationArtifact(version.getRevision(), compiled.configuration(),
                artifactCompiler.turtle(compiled.assertions()), artifactCompiler.turtle(shapes), null, null);
        if (!versionRepository.update(version, expectedRevision)
                || !configurationStore.saveDraft(version.getTenantId(), version.getId(), artifact)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return workspace(version, snapshot, artifact);
    }

    public void verifyCandidate(String versionId, long expectedRevision, OntologyConfiguration configuration) {
        SemanticVersion version = version(versionId);
        requireEditable(version, expectedRevision, false);
        compile(ref(version), configuration, authorizedSnapshot(version));
        if (version(versionId).getRevision() != expectedRevision) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public OntologyArtifacts artifacts(String versionId) {
        SemanticVersion version = versionService.lock(normalizedId(versionId));
        authorizedSnapshot(version);
        OntologyConfigurationArtifact artifact = readArtifact(version);
        if (artifact == null) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "先保存结构化草稿再导出配置");
        }
        return new OntologyArtifacts(artifact.ontologyTurtle(), artifact.shapesTurtle());
    }

    /** 供运行时读取业务定义，排除物理映射。调用方按 Agent 和任务重新核验访问范围。 */
    public ReleasedOntologyConfiguration getReleasedForContext(String versionId) {
        SemanticVersion version = version(versionId);
        OntologyConfigurationArtifact artifact = readArtifact(version);
        if (version.getStatus() != SemanticVersionStatus.PUBLISHED || artifact == null || !artifact.published()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "该版本尚无可用的业务本体发布产物");
        }
        return new ReleasedOntologyConfiguration(version.getTenantId(), version.getModelId(), version.getId(),
                artifact.revision(), artifact.publishedChecksum(), redactMappings(artifact.configuration()));
    }

    private OntologyConfiguration redactMappings(OntologyConfiguration source) {
        List<Concept> concepts = source.classes().stream()
                .map(entry -> new Concept(entry.id(), entry.name(), entry.label(), entry.description(), null)).toList();
        List<Property> properties = source.properties().stream()
                .map(entry -> new Property(entry.id(), entry.classId(), entry.name(), entry.label(),
                        entry.dataType(), entry.required(), null)).toList();
        return new OntologyConfiguration(concepts, properties, source.relationships());
    }

    private OntologyConfigurationWorkspace publishedWorkspace(
            SemanticVersion version, OntologyConfigurationArtifact artifact) {
        if (authorization.hasPermission("database:source:view")) {
            try {
                return workspace(version, authorizedSnapshot(version), artifact);
            } catch (BusinessException exception) {
                if (exception.getCode() != ResultCode.MCP_SERVER_NOT_FOUND.getCode()
                        && exception.getCode() != ResultCode.SCHEMA_SNAPSHOT_NOT_FOUND.getCode()) {
                    throw exception;
                }
            }
        }
        OntologyConfiguration configuration = artifact == null ? null : redactMappings(artifact.configuration());
        return new OntologyConfigurationWorkspace(version.getRevision(), false, configuration, null, null,
                readiness(artifact));
    }

    private SemanticVersion version(String versionId) {
        return versionService.get(normalizedId(versionId));
    }

    private String normalizedId(String versionId) {
        try {
            return UUID.fromString(versionId).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND);
        }
    }

    private void requireEditable(SemanticVersion version, long expectedRevision, boolean lockModel) {
        if (version.getRevision() != expectedRevision) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        var model = (lockModel
                ? modelRepository.lockByTenantAndId(version.getTenantId(), version.getModelId())
                : modelRepository.findByTenantAndId(version.getTenantId(), version.getModelId()))
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_MODEL_NOT_FOUND));
        if (model.getStatus() != SemanticModelStatus.ACTIVE || !version.isEditable()
                || (readArtifact(version) == null && hasLegacyGraph(version))) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "当前版本的本体不能通过工作台编辑");
        }
    }

    private boolean hasLegacyGraph(SemanticVersion version) {
        // 结构化新草稿同时保存空产物。仅历史草稿需要检查旧 TDB2。
        return !legacyStore.querySubgraph(ref(version), new GraphQuery(null, null, 0, 1, 1)).nodes().isEmpty();
    }

    private OntologyConfigurationArtifact readArtifact(SemanticVersion version) {
        OntologyConfigurationArtifact artifact = configurationStore.find(version.getTenantId(), version.getId()).orElse(null);
        if (artifact != null && (artifact.revision() > version.getRevision()
                || (artifact.published() && !artifact.publishedChecksum().equals(version.getChecksum())))) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return artifact;
    }

    SchemaSnapshot authorizedSnapshot(SemanticVersion version) {
        if (version.getSourceSnapshotId() == null) {
            return null;
        }
        SchemaSnapshot snapshot = snapshotRepository.findByTenantAndId(version.getTenantId(), version.getSourceSnapshotId())
                .orElseThrow(() -> new BusinessException(ResultCode.SCHEMA_SNAPSHOT_NOT_FOUND));
        return schemaScanService.getSnapshot(snapshot.getSourceId(), snapshot.getId());
    }

    private CompiledOntology compile(
            OntologyGraphRef ref, OntologyConfiguration configuration, SchemaSnapshot snapshot) {
        try {
            CompiledOntology compiled = compiler.compile(ref, configuration, snapshot);
            if (compiled.configuration().queryProfile() != null) {
                queryCompiler.compile(ref, compiled.configuration(), snapshot);
            }
            return compiled;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, exception.getMessage());
        }
    }

    private OntologyGraphRef ref(SemanticVersion version) {
        return new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo());
    }

    private OntologyConfigurationWorkspace workspace(
            SemanticVersion version, SchemaSnapshot snapshot, OntologyConfigurationArtifact artifact) {
        OntologyConfiguration configuration = artifact == null ? null : artifact.configuration();
        if (configuration == null && version.isEditable() && !hasLegacyGraph(version)) {
            configuration = OntologyConfiguration.empty();
        }
        return new OntologyConfigurationWorkspace(version.getRevision(), version.isEditable() && configuration != null,
                configuration, snapshot == null ? null : snapshot.getSourceId(), version.getSourceSnapshotId(),
                readiness(artifact));
    }

    private OntologyReleaseReadiness readiness(OntologyConfigurationArtifact artifact) {
        return artifact == null ? null : OntologyReleaseReadiness.structured(
                artifact.published(), artifact.queryProfile() != null);
    }
}
