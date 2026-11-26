package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyEdge;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyNodeUpdate;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologySubgraph;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyArtifactCompilerPort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;

import static com.schemaplexai.service.semantic.common.SemanticVocabulary.MAPPING_SOURCE;
import static com.schemaplexai.service.semantic.common.SemanticVocabulary.PHYSICAL_FIELD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class OntologyGraphApplicationServiceTest {

    @Mock
    private SemanticVersionRepository versionRepository;
    @Mock
    private OntologyStorePort ontologyStore;
    @Mock
    private OntologyConfigurationStorePort configurationStore;
    @Mock
    private OntologyArtifactCompilerPort artifactCompiler;
    @Mock
    private AuthorizationService authorizationService;
    @Mock
    private DatabaseSourceService databaseSources;

    private OntologyGraphApplicationService service;

    @BeforeEach
    void setUp() {
        service = new OntologyGraphApplicationService(
                versionRepository, ontologyStore, configurationStore, artifactCompiler, authorizationService, databaseSources);
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void writesOnlyEditableVersionOwnedByCurrentTenant() {
        SemanticVersion version = SemanticVersion.createDraft(
                "v1", "tenant-a", "model-1", 1, "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted");
        when(versionRepository.findByTenantModelAndId("tenant-a", "model-1", "v1"))
                .thenReturn(Optional.of(version));
        when(versionRepository.lockByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));

        service.replaceDraft("model-1", "v1", List.of(new OntologyStatement(
                "urn:example:Order", "http://www.w3.org/2000/01/rdf-schema#label",
                OntologyTerm.literal("订单"))));

        verify(ontologyStore).replaceDraft(any());
    }

    @Test
    void rejectsPublishedVersionBeforeTouchingGraphStore() {
        SemanticVersion version = SemanticVersion.restore(
                "v1", "tenant-a", "model-1", 1,
                "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted",
                null,
                SemanticVersionStatus.PUBLISHED, "checksum", 1, null, LocalDateTime.now(), 1);
        when(versionRepository.findByTenantModelAndId("tenant-a", "model-1", "v1"))
                .thenReturn(Optional.of(version));
        when(versionRepository.lockByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.replaceDraft("model-1", "v1", List.of()))
                .isInstanceOf(BusinessException.class);
        verify(ontologyStore, never()).replaceDraft(any());
    }

    @Test
    void hidesLegacyMappingAndPreventsAnUnauthorizedPartialEditFromRemovingIt() {
        String node = "urn:example:Order";
        var ref = new OntologyGraphRef("tenant-a", "model-1", 1);
        var query = new GraphQuery(node, null, 0, 1, 1);
        var version = SemanticVersion.createDraft("v1", ref.tenantId(), ref.modelId(), 1, "urn:example:asserted");
        var edges = List.of(new OntologyEdge(node, MAPPING_SOURCE, OntologyTerm.literal("source-unavailable")),
                new OntologyEdge(node, PHYSICAL_FIELD, OntologyTerm.literal("hidden_field")));
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));
        when(versionRepository.lockByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));
        when(ontologyStore.querySubgraph(ref, query)).thenReturn(new OntologySubgraph(ref, List.of(), edges, 1, false));

        assertThat(service.query("v1", query).edges()).isEmpty();
        var update = new OntologyNodeUpdate("Order", "订单", null, List.of(), null, null, null);
        assertThatThrownBy(() -> service.updateNode("v1", node, update, 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("需要数据源查看权限");
        verify(ontologyStore, never()).updateNode(any(), any(), any());
    }

    @Test
    void withholdsAmbiguousSourceAssertionsEvenWithSourcePermission() {
        String node = "urn:example:Order";
        var ref = new OntologyGraphRef("tenant-a", "model-1", 1);
        var query = new GraphQuery(node, null, 0, 1, 1);
        var version = SemanticVersion.createDraft("v1", ref.tenantId(), ref.modelId(), 1, "urn:example:asserted");
        List<OntologyEdge> edges = List.of(
                new OntologyEdge(node, MAPPING_SOURCE, OntologyTerm.literal(UUID.randomUUID().toString())),
                new OntologyEdge(node, MAPPING_SOURCE, OntologyTerm.literal(UUID.randomUUID().toString())),
                new OntologyEdge(node, PHYSICAL_FIELD, OntologyTerm.literal("hidden_field")));
        when(versionRepository.findByTenantAndId("tenant-a", "v1")).thenReturn(Optional.of(version));
        when(authorizationService.hasPermission("database:source:view")).thenReturn(true);
        when(ontologyStore.querySubgraph(ref, query)).thenReturn(new OntologySubgraph(ref, List.of(), edges, 1, false));

        assertThat(service.query("v1", query).edges()).isEmpty();
        verify(databaseSources, never()).getById(any());
    }
}
