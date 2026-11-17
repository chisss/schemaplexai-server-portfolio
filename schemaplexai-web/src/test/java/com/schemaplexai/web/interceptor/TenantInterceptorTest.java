package com.schemaplexai.web.interceptor;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantInterceptorTest {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    private final TenantInterceptor interceptor = new TenantInterceptor();

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldAllowPublicRequestWithoutTenant() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void shouldRejectAuthenticatedRequestWhenTenantMissing() {
        authenticate("user-1", "OPERATOR");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agents");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(exception).hasMessage("租户上下文缺失");
                });
    }

    @Test
    void shouldRejectNormalUserTenantSwitching() {
        authenticate("user-1", "OPERATOR");
        SecurityUtil.setCurrentTenantId("tenant-a");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agents");
        request.addHeader(TENANT_HEADER, "tenant-b");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(exception).hasMessage("普通用户不允许切换租户");
                });
    }

    @Test
    void shouldAllowSuperAdminToUseHeaderTenant() {
        authenticate("admin-1", CommonConstant.ROLE_SUPER_ADMIN);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/agents");
        request.addHeader(TENANT_HEADER, "tenant-b");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("tenant-b");
    }

    private void authenticate(String userId, String roleCode) {
        SecurityUtil.setCurrentUserId(userId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + roleCode))
                )
        );
    }
}
