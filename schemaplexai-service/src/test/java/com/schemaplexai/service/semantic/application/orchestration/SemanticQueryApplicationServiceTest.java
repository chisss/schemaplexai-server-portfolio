package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNode;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.DIMENSION_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.METRIC_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticQueryApplicationServiceTest {

    @Mock
    private SemanticVersionRepository versionRepository;
    @Mock
    private OntologyStorePort ontologyStore;

    private SemanticQueryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticQueryApplicationService(
                versionRepository, ontologyStore, new SemanticQueryInterpretationService());
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void readsOnlyPublishedVersionAndBuildsCandidatesFromGraph() {
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(publishedVersion()));
        when(ontologyStore.querySubgraph(any(), any())).thenReturn(graph(false));

        var result = service.interpret("订单金额", "v1", "source-1");

        org.assertj.core.api.Assertions.assertThat(result.intent()).isNotNull();
        verify(ontologyStore).querySubgraph(any(), any());
    }

    @Test
    void rejectsDraftWithoutReadingGraph() {
        SemanticVersion draft = SemanticVersion.createDraft(
                "v1", "tenant-a", "model-1", 1, "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted");
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.interpret("订单金额", "v1", "source-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.getCode())
                                .isEqualTo(ResultCode.SEMANTIC_VERSION_STATUS_INVALID.getCode()));
        verify(ontologyStore, never()).querySubgraph(any(), any());
    }

    private SemanticVersion publishedVersion() {
        return SemanticVersion.restore(
                "v1", "tenant-a", "model-1", 1,
                "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted", null,
                SemanticVersionStatus.PUBLISHED, "checksum", 6, null, LocalDateTime.now(), 2);
    }

    private OntologySubgraph graph(boolean hasMore) {
        String metric = "urn:order:amount";
        List<OntologyNode> nodes = List.of(new OntologyNode(metric, "订单金额"));
        List<OntologyEdge> edges = List.of(
                edge(metric, RDF_TYPE, METRIC_CLASS),
                edge(metric, NAME, "订单金额"),
                edge(metric, MAPPING_SOURCE, "source-1"),
                edge(metric, PHYSICAL_OBJECT, "orders"),
                edge(metric, PHYSICAL_FIELD, "amount"));
        return new OntologySubgraph(
                new OntologyGraphRef("tenant-a", "model-1", 1), nodes, edges, nodes.size(), hasMore);
    }

    private OntologyEdge edge(String subject, String predicate, String object) {
        return new OntologyEdge(subject, predicate,
                RDF_TYPE.equals(predicate) ? OntologyTerm.iri(object) : OntologyTerm.literal(object));
    }
}
