package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.CreateSemanticModelCommand;
import com.schemaplexai.service.semantic.application.command.UpdateSemanticModelCommand;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.repository.SemanticModelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticModelServiceTest {

    @Mock
    private SemanticModelRepository repository;

    private SemanticModelApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SemanticModelApplicationService(repository);
        SecurityUtil.setCurrentTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void createsModelForCurrentTenant() {
        when(repository.existsActiveName("tenant-a", "订单", null)).thenReturn(false);

        SemanticModel result = service.create(new CreateSemanticModelCommand("订单", "sales", null));

        ArgumentCaptor<SemanticModel> captor = ArgumentCaptor.forClass(SemanticModel.class);
        verify(repository).insert(captor.capture());
        assertThat(captor.getValue().getTenantId()).isEqualTo("tenant-a");
        assertThat(result.getRevision()).isZero();
    }

    @Test
    void translatesUniqueConstraintRaceIntoDuplicateName() {
        when(repository.existsActiveName("tenant-a", "订单", null)).thenReturn(false);
        org.mockito.Mockito.doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(any(SemanticModel.class));

        assertThatThrownBy(() -> service.create(new CreateSemanticModelCommand("订单", "sales", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ResultCode.SEMANTIC_MODEL_NAME_DUPLICATE.getCode()));
    }

    @Test
    void translatesStaleUpdateIntoRevisionConflict() {
        SemanticModel model = SemanticModel.create("model-1", "tenant-a", "订单", "sales", null);
        when(repository.findByTenantAndId("tenant-a", "model-1")).thenReturn(Optional.of(model));

        assertThatThrownBy(() -> service.update(
                "model-1",
                new UpdateSemanticModelCommand("订单", "sales", null, 1)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ResultCode.SEMANTIC_REVISION_CONFLICT.getCode()));
    }

    @Test
    void translatesDatabaseCompareAndSetMissIntoRevisionConflict() {
        SemanticModel model = SemanticModel.create("model-1", "tenant-a", "订单", "sales", null);
        when(repository.findByTenantAndId("tenant-a", "model-1")).thenReturn(Optional.of(model));
        when(repository.existsActiveName("tenant-a", "订单", "model-1")).thenReturn(false);
        when(repository.update(model, 0)).thenReturn(false);

        assertThatThrownBy(() -> service.update(
                "model-1",
                new UpdateSemanticModelCommand("订单", "sales", null, 0)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo(ResultCode.SEMANTIC_REVISION_CONFLICT.getCode()));
    }

    @Test
    void neverLooksUpModelWithoutTenant() {
        SecurityUtil.clear();

        assertThatThrownBy(() -> service.get("model-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verify(repository, org.mockito.Mockito.never()).findByTenantAndId(any(), any());
    }
}
