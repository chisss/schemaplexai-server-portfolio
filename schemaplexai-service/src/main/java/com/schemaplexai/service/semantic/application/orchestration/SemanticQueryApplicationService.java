package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.query.QueryCandidateRole;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryCandidate;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.AGGREGATION;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.DIMENSION_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.FILTER_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_KIND;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.METRIC_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDFS_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.SKOS_ALT_LABEL;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.TIME_CLASS;

/** 已发布语义版本的自然语言解释编排，不执行数据库查询。 */
@Service
@RequiredArgsConstructor
public class SemanticQueryApplicationService {

    private static final int MAX_GRAPH_NODES = 500;

    private final SemanticVersionRepository versionRepository;
    private final OntologyStorePort ontologyStore;
    private final SemanticQueryInterpretationService interpretationService;

    public SemanticQueryInterpretation interpret(
            String question,
            String semanticVersionId,
            String sourceId) {
        return interpret(question, semanticVersionId, sourceId, null);
    }

    public SemanticQueryInterpretation interpret(
            String question,
            String semanticVersionId,
            String sourceId,
            String context) {
        String tenantId = requireTenantId();
        SemanticVersion version = versionRepository.findByTenantAndId(tenantId, semanticVersionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        if (version.getStatus() != SemanticVersionStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "仅允许解释已发布语义版本");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE);
        }
        OntologySubgraph graph = ontologyStore.querySubgraph(
                new OntologyGraphRef(tenantId, version.getModelId(), version.getVersionNo()),
                new GraphQuery(null, null, 1, 1, MAX_GRAPH_NODES));
        if (graph.hasMore()) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE, "语义版本候选超过解释配额，请缩小语义模型");
        }
        return interpretationService.interpret(withContext(question, context), semanticVersionId, sourceId, candidates(graph));
    }

    private String withContext(String question, String context) {
        if (context == null || context.isBlank()) {
            return question;
        }
        return question + " 补充上下文：" + context.trim();
    }

    private List<SemanticQueryCandidate> candidates(OntologySubgraph graph) {
        Map<String, CandidateBuilder> builders = new HashMap<>();
        graph.nodes().forEach(node -> builders.put(node.iri(), new CandidateBuilder(node.iri())));
        for (OntologyEdge edge : graph.edges()) {
            CandidateBuilder builder = builders.get(edge.subjectIri());
            if (builder == null) {
                continue;
            }
            String value = edge.object().value();
            switch (edge.predicateIri()) {
                case NAME, RDFS_LABEL, SKOS_ALT_LABEL -> builder.aliases.add(value);
                case RDF_TYPE -> builder.role = role(value);
                case MAPPING_SOURCE -> builder.sourceId = value;
                case PHYSICAL_OBJECT -> builder.physicalObject = value;
                case PHYSICAL_FIELD -> builder.physicalField = value;
                case MAPPING_KIND -> builder.mappingKind = value;
                case AGGREGATION -> builder.aggregation = value;
                default -> {
                }
            }
        }
        return builders.values().stream()
                .map(CandidateBuilder::build)
                .filter(candidate -> candidate.role() != QueryCandidateRole.UNKNOWN)
                .toList();
    }

    private QueryCandidateRole role(String typeIri) {
        return switch (typeIri) {
            case METRIC_CLASS -> QueryCandidateRole.METRIC;
            case DIMENSION_CLASS -> QueryCandidateRole.DIMENSION;
            case FILTER_CLASS -> QueryCandidateRole.FILTER;
            case TIME_CLASS -> QueryCandidateRole.TIME;
            default -> QueryCandidateRole.UNKNOWN;
        };
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }

    private static final class CandidateBuilder {
        private final String iri;
        private final Set<String> aliases = new LinkedHashSet<>();
        private QueryCandidateRole role = QueryCandidateRole.UNKNOWN;
        private String sourceId;
        private String physicalObject;
        private String physicalField;
        private String mappingKind;
        private String aggregation;

        private CandidateBuilder(String iri) {
            this.iri = iri;
        }

        private SemanticQueryCandidate build() {
            return new SemanticQueryCandidate(
                    iri,
                    role,
                    new ArrayList<>(aliases),
                    sourceId,
                    physicalObject,
                    physicalField,
                    mappingKind,
                    aggregation);
        }
    }
}
