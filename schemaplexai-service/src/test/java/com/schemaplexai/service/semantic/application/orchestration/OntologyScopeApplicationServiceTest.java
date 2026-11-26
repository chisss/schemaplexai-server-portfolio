package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.scope.OntologyScope;
import com.schemaplexai.service.semantic.domain.repository.OntologyScopeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OntologyScopeApplicationServiceTest {

    private final OntologyScopeRepository repository = mock(OntologyScopeRepository.class);
    private final OntologyScopeApplicationService service = new OntologyScopeApplicationService(repository);

    @AfterEach
    void clearTenant() {
        SecurityUtil.clear();
    }

    @Test
    void createsWithinCurrentTenantAndTrimsNames() {
        SecurityUtil.setCurrentTenantId("tenant-a");
        OntologyScope scope = new OntologyScope("scope-1", "销售中台", "订单履约");
        when(repository.createOrGet("tenant-a", "销售中台", "订单履约")).thenReturn(scope);

        assertThat(service.create("  销售中台 ", " 订单履约 ")).isEqualTo(scope);
    }

    @Test
    void requiresScopeInCurrentTenant() {
        SecurityUtil.setCurrentTenantId("tenant-b");
        String id = "43fd7384-072e-4ae9-ba3a-23118dce613e";
        when(repository.find("tenant-b", id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(id))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
    }

    @Test
    void rejectsMissingTenantBeforeRepositoryRead() {
        assertThatThrownBy(service::list).isInstanceOf(BusinessException.class);
        verifyNoInteractions(repository);
    }
}
