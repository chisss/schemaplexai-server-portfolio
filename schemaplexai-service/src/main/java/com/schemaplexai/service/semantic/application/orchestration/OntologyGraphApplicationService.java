package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 本体图控制面编排，负责租户和版本状态校验后调用图存储端口。 */
@Service
@RequiredArgsConstructor
public class OntologyGraphApplicationService {

    private final SemanticVersionRepository versionRepository;
    private final OntologyStorePort ontologyStore;

    public void replaceDraft(String modelId, String versionId, List<OntologyStatement> statements) {
        SemanticVersion version = requireVersion(modelId, versionId);
        if (!version.isEditable()) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID);
        }
        ontologyStore.replaceDraft(new OntologyGraph(
                new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo()),
                statements));
    }

    public OntologySubgraph query(String modelId, String versionId, GraphQuery query) {
        SemanticVersion version = requireVersion(modelId, versionId);
        return ontologyStore.querySubgraph(
                new OntologyGraphRef(version.getTenantId(), version.getModelId(), version.getVersionNo()), query);
    }

    private SemanticVersion requireVersion(String modelId, String versionId) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return versionRepository.findByTenantModelAndId(tenantId.trim(), modelId, versionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
    }
}
