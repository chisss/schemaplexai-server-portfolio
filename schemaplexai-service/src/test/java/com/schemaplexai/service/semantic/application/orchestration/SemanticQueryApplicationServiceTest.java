package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNode;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.PublishedQueryInterpretationService;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.METRIC_CLASS;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.NAME;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_OBJECT;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.RDF_TYPE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock
    private OntologyConfigurationStorePort configurationStore;
    @Mock
    private SchemaSnapshotRepository snapshots;
    @Mock
    private SemanticQuerySourcePort sourcePort;

    private SemanticQueryApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticQueryApplicationService(
                versionRepository, ontologyStore, new SemanticQueryInterpretationService(), configurationStore,
                new PublishedQueryInterpretationService(Clock.systemUTC()), snapshots, sourcePort);
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

        assertThat(result.intent()).isNotNull();
        verify(ontologyStore).querySubgraph(any(), any());
    }

    @Test
    void contextOnlyReleaseDoesNotUseLegacyGraph() {
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(publishedVersion()));
        when(configurationStore.find("tenant-a", "v1")).thenReturn(Optional.of(
                new OntologyConfigurationArtifact(1, null, null, null, null, "checksum")));

        assertThatThrownBy(() -> service.interpret("订单金额", "v1", "source-1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尚未具备查询口径");
        verify(ontologyStore, never()).querySubgraph(any(), any());
    }

    @Test
    void validatesMysqlReleaseAgainstTheCurrentConnectorAndAuthorizedColumns() {
        SecurityUtil.setCurrentUserId("employee");
        var version = SemanticVersion.restore("v1", "tenant-a", "model-1", 1, "urn:release", "snapshot",
                SemanticVersionStatus.PUBLISHED, "checksum", 6, null, LocalDateTime.now(), 2);
        var profile = new PublishedQueryProfile("source-1", "snapshot", "fingerprint", "mysql", "sales.orders", "UTC",
                List.of(new PublishedQueryProfile.Measure("amount", "urn:amount", "Amount", "订单金额", "amount",
                        "decimal", "decimal", "sum")),
                List.of(new PublishedQueryProfile.Dimension("city", "urn:city", "City", "城市", "city", "string", "varchar")),
                List.of(), null);
        var snapshot = SchemaSnapshot.create("snapshot", "tenant-a", "source-1", "fingerprint",
                new DatabaseSchema("mysql", null, "sales", List.of(), Set.of(), List.of()), LocalDateTime.now());
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));
        when(configurationStore.find("tenant-a", "v1")).thenReturn(Optional.of(
                new OntologyConfigurationArtifact(2, null, null, null, null, "checksum", profile)));
        when(snapshots.findByTenantAndId("tenant-a", "snapshot")).thenReturn(Optional.of(snapshot));
        when(sourcePort.databaseType("source-1")).thenReturn("mysql");

        var allowed = new SemanticQueryDatasetAccess("dataset", 4L, "v1", "source-1",
                "sales.orders", List.of("amount", "city"));
        var result = service.interpret("按城市统计订单金额总和", "v1", "source-1", null, allowed);

        assertThat(result.intent().metrics().getFirst().aggregation()).isEqualTo("sum");
        assertThat(result.intent().dimensions().getFirst().physicalField()).isEqualTo("city");
        var denied = new SemanticQueryDatasetAccess("dataset", 5L, "v1", "source-1", "sales.orders", List.of("amount"));
        assertThatThrownBy(() -> service.interpret("按城市统计订单金额总和", "v1", "source-1", null, denied))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        when(sourcePort.databaseType("source-1")).thenReturn("postgresql");
        assertThatThrownBy(() -> service.interpret("按城市统计订单金额总和", "v1", "source-1", null, allowed))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE.getCode()));
        verify(ontologyStore, never()).querySubgraph(any(), any());
    }

    @Test
    void rejectsDraftWithoutReadingGraph() {
        SemanticVersion draft = SemanticVersion.createDraft(
                "v1", "tenant-a", "model-1", 1, "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted");
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.interpret("订单金额", "v1", "source-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode())
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
