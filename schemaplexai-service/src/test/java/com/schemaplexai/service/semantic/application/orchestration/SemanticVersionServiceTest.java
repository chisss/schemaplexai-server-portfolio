package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.port.SemanticGraphLocator;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticVersionServiceTest {

    @Mock
    private SemanticModelRepository modelRepository;
    @Mock
    private SemanticVersionRepository versionRepository;
    @Mock
    private SemanticGraphLocator graphLocator;

    private SemanticVersionApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticVersionApplicationService(modelRepository, versionRepository, graphLocator);
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void createsNextDraftWithServerOwnedGraphIri() {
        SemanticModel model = SemanticModel.create("model-1", "tenant-a", "订单", "sales", null);
        when(modelRepository.findByTenantAndId("tenant-a", "model-1")).thenReturn(Optional.of(model));
        when(versionRepository.nextVersionNo("tenant-a", "model-1")).thenReturn(2);
        when(graphLocator.assertedGraph("tenant-a", "model-1", 2)).thenReturn(
                "urn:spx:tenant:tenant-a:semantic:model-1:v:2:asserted");

        SemanticVersion result = service.createDraft("model-1");

        ArgumentCaptor<SemanticVersion> captor = ArgumentCaptor.forClass(SemanticVersion.class);
        verify(versionRepository).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(result.getVersionNo()).isEqualTo(2);
        assertThat(result.getGraphIri()).endsWith(":v:2:asserted");
    }
}
