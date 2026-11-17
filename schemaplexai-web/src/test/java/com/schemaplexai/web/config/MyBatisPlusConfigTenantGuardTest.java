package com.schemaplexai.web.config;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.common.util.SystemTenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MyBatisPlusConfigTenantGuardTest {

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
        SystemTenantContext.clear();
    }

    @Test
    void shouldRejectTenantTableWhenTenantContextMissing() {
        assertThatThrownBy(() -> MyBatisPlusConfig.shouldIgnoreTenantTable("sf_agent"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(exception).hasMessage("租户上下文缺失");
                });
    }

    @Test
    void shouldAllowGlobalTableWithoutTenant() {
        assertThat(MyBatisPlusConfig.shouldIgnoreTenantTable("sf_permission")).isTrue();
    }

    @Test
    void shouldAllowSystemOperationWithoutTenant() {
        SystemTenantContext.setSystemOperation(true);

        assertThat(MyBatisPlusConfig.shouldIgnoreTenantTable("sf_agent")).isTrue();
    }

    @Test
    void shouldFilterTenantTableWhenTenantContextPresent() {
        SecurityUtil.setCurrentTenantId("tenant-1");

        assertThat(MyBatisPlusConfig.shouldIgnoreTenantTable("sf_agent")).isFalse();
    }
}
