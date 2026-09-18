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

/** 订单语义模型的 Phase 1 端到端发布验收。 */
@ExtendWith(MockitoExtension.class)
class OrderSemanticAcceptanceTest {

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
        model = SemanticModel.create("model-order", "tenant-a", "订单分析", "sales", "订单金额与完成趋势");
        version = SemanticVersion.createDraft(
                "version-order", "tenant-a", "model-order", 1,
                "urn:spx:tenant:tenant-a:semantic:model-order:v:1:asserted");
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void orderModelRequiresLabelBeforeActivationAndPreservesActiveVersionOnFailure() {
        when(modelRepository.findByTenantAndId("tenant-a", "model-order")).thenReturn(Optional.of(model));
        when(versionRepository.findByTenantModelAndId("tenant-a", "model-order", "version-order"))
                .thenReturn(Optional.of(version));
        when(versionRepository.update(any(), anyLong())).thenReturn(true);
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-order", 1),
                "order-invalid", false, "<urn:order:Order> 缺少 label", null, 0));

        SemanticVersion invalid = service.publish(
                "model-order", "version-order", new PublishSemanticVersionCommand(0, 0, List.of()));

        assertThat(invalid.getStatus()).isEqualTo(SemanticVersionStatus.INVALID);
        assertThat(model.getActiveVersionId()).isNull();
        verify(modelRepository, never()).update(any(), anyLong());
        verify(publishPort).discard(any());

        when(modelRepository.update(any(), anyLong())).thenReturn(true);
        when(publishPort.prepare(any(), any(), any())).thenReturn(new PublishPreparation(
                new OntologyGraphRef("tenant-a", "model-order", 1),
                "order-valid", true, null, "sha256:order", 4));

        SemanticVersion published = service.publish(
                "model-order", "version-order",
                new PublishSemanticVersionCommand(2, 0, List.of(labelShape())));

        assertThat(published.getStatus()).isEqualTo(SemanticVersionStatus.PUBLISHED);
        assertThat(model.getActiveVersionId()).isEqualTo("version-order");
        verify(publishPort).commit(any());
    }

    @Test
    void staleRevisionIsRejectedBeforeTouchingOntologyStore() {
        when(modelRepository.findByTenantAndId("tenant-a", "model-order")).thenReturn(Optional.of(model));
        when(versionRepository.findByTenantModelAndId("tenant-a", "model-order", "version-order"))
                .thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.publish(
                "model-order", "version-order", new PublishSemanticVersionCommand(99, 0, List.of(labelShape()))))
                .isInstanceOf(BusinessException.class);

        verify(publishPort, never()).prepare(any(), any(), any());
        assertThat(model.getActiveVersionId()).isNull();
    }

    @Test
    void tenantCannotResolveAnotherTenantOrderVersion() {
        SecurityUtil.setCurrentTenantId("tenant-b");
        when(versionRepository.findByTenantAndId("tenant-b", "version-order")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate("version-order", 0))
                .isInstanceOf(BusinessException.class);
        verify(publishPort, never()).prepare(any(), any(), any());
    }

    private OntologyStatement labelShape() {
        return new OntologyStatement(
                "urn:shape:Order",
                "http://www.w3.org/2000/01/rdf-syntax-ns#type",
                OntologyTerm.iri("http://www.w3.org/ns/shacl#NodeShape"));
    }
}
