package com.schemaplexai.service.security.impl;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.PermissionCodeEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.common.PermissionLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationServiceImplTest {

    @Mock
    private PermissionLoader permissionLoader;

    private AuthorizationServiceImpl authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationServiceImpl(permissionLoader);
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldDenyWhenUserMissing() {
        assertThat(authorizationService.hasPermission(PermissionCodeEnum.COST_VIEW.getCode())).isFalse();
    }

    @Test
    void shouldDenyBlankPermissionCode() {
        authenticate("user-1", "USER");

        assertThat(authorizationService.hasPermission(" ")).isFalse();
        verify(permissionLoader, never()).loadPermissionCodeSet("user-1");
    }

    @Test
    void shouldAllowSuperAdminWithoutLoadingPermissions() {
        authenticate("admin-1", CommonConstant.ROLE_SUPER_ADMIN);

        assertThat(authorizationService.hasPermission(PermissionCodeEnum.SECURITY_POLICY_MANAGE.getCode())).isTrue();
        verify(permissionLoader, never()).loadPermissionCodeSet("admin-1");
    }

    @Test
    void shouldUseLoadedPermissionCodesForNormalUser() {
        authenticate("user-1", "OPERATOR");
        when(permissionLoader.loadPermissionCodeSet("user-1"))
                .thenReturn(Set.of(PermissionCodeEnum.COST_VIEW.getCode()));

        assertThat(authorizationService.hasPermission(PermissionCodeEnum.COST_VIEW.getCode())).isTrue();
        assertThat(authorizationService.hasPermission(PermissionCodeEnum.AGENT_EXECUTE.getCode())).isFalse();
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
