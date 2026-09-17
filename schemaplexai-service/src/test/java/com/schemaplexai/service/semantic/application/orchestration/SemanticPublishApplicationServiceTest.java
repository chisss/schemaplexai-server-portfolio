package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.PublishSemanticVersionCommand;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyStatement;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyTerm;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishPreparation;
import com.schemaplexai.service.semantic.domain.port.SemanticPublishPort;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticPublishApplicationServiceTest {

    @Mock
    private SemanticModelRepository modelRepository;
    @Mock
    private SemanticVersionRepository versionRepository;
    @Mock
    private SemanticPublishPort publishPort;

    private SemanticPublishApplicationService service;
    private SemanticModel model;
    private SemanticVersion version;

    @BeforeEach
    void setUp() {
        service = new SemanticPublishApplicationService(modelRepository, versionRepository, publishPort);
        model = SemanticModel.create("model-1", "tenant-a", "订单", "sales", null);
        version = SemanticVersion.createDraft(
                "version-1", "tenant-a", "model-1", 1,
                "urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted");
        SecurityUtil.setCurrentTenantId("tenant-a");
        when(modelRepository.findByTenantAndId("tenant-a", "model-1")).thenReturn(Optional.of(model));
        when(versionRepository.findByTenantModelAndId("tenant-a", "model-1", "version-1"))
                .thenReturn(Optional.of(version));
        when(versionRepository.update(any(), anyLong())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void publishesConformingVersionAndActivatesModel() {
        when(modelRepository.update(any(), anyLong())).thenReturn(true);
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-1", 1),
                "operation-1", true, null, "sha256:abc", 12));

        SemanticVersion result = service.publish(
                "model-1", "version-1", new PublishSemanticVersionCommand(0, 0, shapes()));

        assertThat(result.getStatus()).isEqualTo(SemanticVersionStatus.PUBLISHED);
        assertThat(result.getTripleCount()).isEqualTo(12);
        assertThat(model.getActiveVersionId()).isEqualTo("version-1");
        verify(publishPort).commit(any());
        verify(publishPort, never()).discard(any());
    }

    @Test
    void marksVersionInvalidAndDoesNotActivateModel() {
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-1", 1),
                "operation-2", false, "缺少 label", null, 0));

        SemanticVersion result = service.publish(
                "model-1", "version-1", new PublishSemanticVersionCommand(0, 0, shapes()));

        assertThat(result.getStatus()).isEqualTo(SemanticVersionStatus.INVALID);
        assertThat(result.getValidationReport()).isEqualTo("缺少 label");
        verify(modelRepository, never()).update(any(), anyLong());
        verify(publishPort).discard(any());
        verify(publishPort, never()).commit(any());
    }

    @Test
    void discardsPreparationWhenModelRevisionConflicts() {
        when(modelRepository.update(any(), anyLong())).thenReturn(false);
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-1", 1),
                "operation-3", true, null, "sha256:abc", 12));

        assertThatThrownBy(() -> service.publish(
                        "model-1", "version-1", new PublishSemanticVersionCommand(0, 0, shapes())))
                .isInstanceOf(BusinessException.class);

        verify(publishPort).discard(any());
        verify(publishPort, never()).commit(any());
    }

    @Test
    void discardsInvalidPreparationWhenVersionUpdateConflicts() {
        when(versionRepository.update(any(), anyLong())).thenReturn(true, false);
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-1", 1),
                "operation-4", false, "缺少 label", null, 0));

        assertThatThrownBy(() -> service.publish(
                        "model-1", "version-1", new PublishSemanticVersionCommand(0, 0, shapes())))
                .isInstanceOf(BusinessException.class);

        verify(publishPort).discard(any());
        verify(publishPort, never()).commit(any());
    }

    private List<OntologyStatement> shapes() {
        return List.of(new OntologyStatement(
                "urn:shape:Order",
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                OntologyTerm.iri("http://www.w3.org/ns/shacl#NodeShape")));
    }
}
