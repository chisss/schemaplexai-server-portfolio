package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

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

    private OntologyGraphApplicationService service;

    @BeforeEach
    void setUp() {
        service = new OntologyGraphApplicationService(versionRepository, ontologyStore);
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

        assertThatThrownBy(() -> service.replaceDraft("model-1", "v1", List.of()))
                .isInstanceOf(BusinessException.class);
        verify(ontologyStore, never()).replaceDraft(any());
    }
}
