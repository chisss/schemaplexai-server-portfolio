package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNodeUpdate;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;

/** 本体图控制面编排，负责租户和版本状态校验后调用图存储端口。 */
@Service
@RequiredArgsConstructor
public class OntologyGraphApplicationService {

    private static final Set<String> MAPPING_PREDICATES = Set.of(MAPPING_KIND, MAPPING_SOURCE, PHYSICAL_OBJECT, PHYSICAL_FIELD);

    private final SemanticVersionRepository versionRepository;
    private final OntologyStorePort ontologyStore;
    private final OntologyConfigurationStorePort configurationStore;
    private final OntologyArtifactCompilerPort artifactCompiler;
    private final AuthorizationService authorizationService;
    private final DatabaseSourceService databaseSources;

    @Transactional(rollbackFor = Exception.class)
    public void replaceDraft(String modelId, String versionId, List<OntologyStatement> statements) {
        SemanticVersion version = requireVersion(modelId, versionId);
        version = versionRepository.lockByTenantAndId(version.getTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        if (!version.isEditable()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID);
        }
        requireLegacy(version);
        ontologyStore.replaceDraft(new OntologyGraph(
                new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo()),
                statements));
    }

    public OntologySubgraph query(String modelId, String versionId, GraphQuery query) {
        return queryVersion(requireVersion(modelId, versionId), query);
    }

    public OntologySubgraph query(String versionId, GraphQuery query) {
        return queryVersion(requireVersion(versionId), query);
    }

    private OntologySubgraph queryVersion(SemanticVersion version, GraphQuery query) {
        var ref = new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo());
        var artifact = configurationStore.find(version.getTenantId(), version.getId()).orElse(null);
        boolean includeMappings = authorizationService.hasPermission("database:source:view");
        if (artifact == null) {
            OntologySubgraph graph = ontologyStore.querySubgraph(ref, query);
            return filterMappings(graph, includeMappings);
        }
        if (artifact.revision() > version.getRevision()
                || (artifact.published() && !artifact.publishedChecksum().equals(version.getChecksum()))) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return filterMappings(artifactCompiler.query(ref, artifact.ontologyTurtle(), query, includeMappings), includeMappings);
    }

    @Transactional(rollbackFor = Exception.class)
    public SemanticVersion updateNode(
            String versionId,
            String nodeIri,
            OntologyNodeUpdate update,
            long expectedRevision) {
        SemanticVersion version = versionRepository.lockByTenantAndId(requireTenantId(), versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        requireLegacy(version);
        var ref = new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo());
        try {
            version.recordDraftEdit(expectedRevision);
        } catch (IllegalStateException exception) {
            throw translateStateException(exception);
        }
        verifyMappingEdit(ref, nodeIri, update);
        if (!versionRepository.update(version, expectedRevision)) {
            throw new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        try {
            ontologyStore.updateNode(ref, nodeIri, update);
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("node not found")) {
                throw new BusinessException(ResultCode.SEMANTIC_NODE_NOT_FOUND);
            }
            throw exception;
        }
        return version;
    }

    private void verifyMappingEdit(OntologyGraphRef ref, String nodeIri, OntologyNodeUpdate update) {
        boolean viewSources = authorizationService.hasPermission("database:source:view");
        if (update.mapping() != null && (!viewSources || !hasVisibleSource(update.mapping().sourceId()))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "修改物理映射需要数据源查看权限");
        }
        var current = ontologyStore.querySubgraph(ref, new GraphQuery(nodeIri, null, 0, 1, 1));
        if (filterMappings(current, viewSources).edges().size() < current.edges().size()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "修改物理映射需要数据源查看权限");
        }
    }

    private OntologySubgraph filterMappings(OntologySubgraph graph, boolean viewSources) {
        Map<String, Boolean> sourceVisibility = new HashMap<>();
        Map<String, Set<String>> sources = graph.edges().stream()
                .filter(edge -> viewSources && MAPPING_SOURCE.equals(edge.predicateIri()))
                .collect(Collectors.groupingBy(edge -> edge.subjectIri(),
                        Collectors.mapping(edge -> edge.object().value(), Collectors.toSet())));
        var visibleSubjects = sources.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1
                        && sourceVisibility.computeIfAbsent(entry.getValue().iterator().next(), this::hasVisibleSource))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        var edges = graph.edges().stream()
                .filter(edge -> !MAPPING_PREDICATES.contains(edge.predicateIri()) || visibleSubjects.contains(edge.subjectIri()))
                .toList();
        return new OntologySubgraph(graph.ref(), graph.nodes(), edges, graph.totalNodes(), graph.hasMore());
    }

    private boolean hasVisibleSource(String sourceId) {
        String normalized;
        try {
            normalized = UUID.fromString(sourceId).toString();
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (!normalized.equalsIgnoreCase(sourceId)) {
            return false;
        }
        try {
            databaseSources.getById(normalized);
            return true;
        } catch (BusinessException exception) {
            if (exception.getCode() == ResultCode.MCP_SERVER_NOT_FOUND.getCode()) {
                return false;
            }
            throw exception;
        }
    }

    private void requireLegacy(SemanticVersion version) {
        if (configurationStore.find(version.getTenantId(), version.getId()).isPresent()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "请在本体配置工作台中编辑该版本");
        }
    }

    private SemanticVersion requireVersion(String modelId, String versionId) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return versionRepository.findByTenantModelAndId(tenantId.trim(), modelId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    private SemanticVersion requireVersion(String versionId) {
        String tenantId = requireTenantId();
        return versionRepository.findByTenantAndId(tenantId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }

    private BusinessException translateStateException(IllegalStateException exception) {
        if (exception.getMessage() != null && exception.getMessage().contains("revision conflict")) {
            return new BusinessException(ResultCode.SEMANTIC_REVISION_CONFLICT);
        }
        return new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, exception.getMessage());
    }
}
