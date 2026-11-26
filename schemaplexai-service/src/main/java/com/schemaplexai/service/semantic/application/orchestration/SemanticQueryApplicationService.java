package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.query.QueryCandidateRole;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryCandidate;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.PublishedQueryInterpretationService;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private final OntologyConfigurationStorePort configurationStore;
    private final PublishedQueryInterpretationService publishedInterpretationService;
    private final SchemaSnapshotRepository snapshots;
    private final SemanticQuerySourcePort sourcePort;

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
        return interpret(question, semanticVersionId, sourceId, context, null);
    }

    public SemanticQueryInterpretation interpret(String question, String semanticVersionId, String sourceId,
                                                 String context, SemanticQueryDatasetAccess access) {
        String tenantId = requireTenantId();
        if (sourceId == null || sourceId.isBlank()) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE);
        }
        if (access != null) {
            access.requireBinding(semanticVersionId, sourceId);
            if (semanticVersionId == null && access.canExplore()) {
                return SemanticQueryInterpretation.noMatch(withContext(question, context), null, sourceId);
            }
        }
        if (semanticVersionId == null) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND);
        }
        SemanticVersion version = versionRepository.findByTenantAndId(tenantId, semanticVersionId)
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_VERSION_NOT_FOUND));
        if (version.getStatus() != SemanticVersionStatus.PUBLISHED) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "仅允许解释已发布语义版本");
        }
        var artifact = configurationStore.find(tenantId, semanticVersionId);
        if (artifact.isPresent()) {
            return interpretPublished(withContext(question, context), sourceId, version, artifact.get(), access);
        }
        OntologySubgraph graph = ontologyStore.querySubgraph(
                new OntologyGraphRef(tenantId, version.getModelId(), version.getVersionNo()),
                new GraphQuery(null, null, 1, 1, MAX_GRAPH_NODES));
        if (graph.hasMore()) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE, "语义版本候选超过解释配额，请缩小语义模型");
        }
        var result = interpretationService.interpret(withContext(question, context), semanticVersionId, sourceId, candidates(graph));
        if (access != null && result.intent() != null) {
            access.requireIntent(result.intent());
        }
        return result;
    }

    private SemanticQueryInterpretation interpretPublished(String question, String sourceId, SemanticVersion version,
                                                          OntologyConfigurationArtifact artifact,
                                                          SemanticQueryDatasetAccess access) {
        var profile = artifact.queryProfile();
        if (!artifact.published() || profile == null) {
            throw new BusinessException(ResultCode.SEMANTIC_VERSION_STATUS_INVALID, "该业务本体尚未具备查询口径");
        }
        String userId = SecurityUtil.getCurrentUserId();
        if (access == null || userId == null || userId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请使用当前账号已获授权的数据集查询业务本体");
        }
        if (!Objects.equals(version.getChecksum(), artifact.publishedChecksum())
                || !sourceId.equals(profile.sourceId()) || !access.physicalObject().equals(profile.physicalObject())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "数据集与已发布查询口径的来源不一致");
        }
        if (!Objects.equals(version.getSourceSnapshotId(), profile.sourceSnapshotId())
                || snapshots.findByTenantAndId(version.getTenantId(), profile.sourceSnapshotId())
                    .filter(snapshot -> sourceId.equals(snapshot.getSourceId())
                        && profile.sourceFingerprint().equals(snapshot.getFingerprint())
                        && matchesPublishedDialect(profile, snapshot.getSchema().databaseType())).isEmpty()
                || !matchesPublishedDialect(profile, sourcePort.databaseType(sourceId))) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE, "查询口径的数据源或 Schema 快照已变化");
        }
        String scope = List.of(version.getTenantId(), userId, access.datasetId(), Long.toString(access.revision()),
                        artifact.publishedChecksum(), profile.sourceFingerprint()).stream()
                .map(value -> value.length() + ":" + value).collect(Collectors.joining());
        var result = publishedInterpretationService.interpret(question, version.getId(), profile, scope);
        if (result.status() == QueryInterpretationStatus.NO_MATCH && recognizesUnmappedTerm(question, artifact.configuration())) {
            return SemanticQueryInterpretation.clarification(question, version.getId(), sourceId, List.of(),
                    List.of("问题涉及已发布业务术语，请明确可查询的指标与条件"));
        }
        if (result.intent() != null) {
            access.requireIntent(result.intent());
        }
        return result;
    }

    private boolean recognizesUnmappedTerm(String question, OntologyConfiguration configuration) {
        return configuration != null && configuration.classes().stream().anyMatch(concept ->
                mentions(question, concept.name()) || mentions(question, concept.label()))
                || configuration != null && configuration.properties().stream().anyMatch(property ->
                    mentions(question, property.name()) || mentions(question, property.label()));
    }

    private boolean mentions(String question, String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalized = term.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(normalized) + "(?![a-z0-9_])")
                .matcher(question.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")).find();
    }

    private boolean matchesPublishedDialect(PublishedQueryProfile profile, String databaseType) {
        try {
            var dialect = QueryDialect.fromDatabaseType(databaseType);
            return dialect.supportsPublishedQuery() && dialect.databaseType().equals(profile.databaseType());
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
