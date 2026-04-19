package com.schemaplexai.web.filter;

import com.schemaplexai.common.util.JwtUtil;
import com.schemaplexai.common.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String JWT_SECRET = "unit-test-secret-key-at-least-32-chars";

    @Test
    void shouldAuthenticateAccessToken() throws Exception {
        JwtAuthenticationFilter filter = buildFilter();
        String accessToken = JwtUtil.generateAccessToken(
                "user-1",
                "tenant-1",
                List.of("admin", "developer"),
                JWT_SECRET,
                60_000
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspace/list");
        request.addHeader("Authorization", "Bearer " + accessToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authenticationRef = new AtomicReference<>();
        AtomicReference<String> userIdRef = new AtomicReference<>();
        AtomicReference<String> tenantIdRef = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            authenticationRef.set(SecurityContextHolder.getContext().getAuthentication());
            userIdRef.set(SecurityUtil.getCurrentUserId());
            tenantIdRef.set(SecurityUtil.getCurrentTenantId());
        });

        assertThat(authenticationRef.get()).isNotNull();
        assertThat(authenticationRef.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_admin", "ROLE_developer");
        assertThat(userIdRef.get()).isEqualTo("user-1");
        assertThat(tenantIdRef.get()).isEqualTo("tenant-1");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(SecurityUtil.getCurrentUserId()).isNull();
        assertThat(SecurityUtil.getCurrentTenantId()).isNull();
    }

    @Test
    void shouldRejectRefreshTokenAsAuthenticationCredential() throws Exception {
        JwtAuthenticationFilter filter = buildFilter();
        String refreshToken = JwtUtil.generateRefreshToken("user-1", JWT_SECRET, 60_000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspace/list");
        request.addHeader("Authorization", "Bearer " + refreshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Authentication> authenticationRef = new AtomicReference<>();
        AtomicReference<String> userIdRef = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            authenticationRef.set(SecurityContextHolder.getContext().getAuthentication());
            userIdRef.set(SecurityUtil.getCurrentUserId());
        });

        assertThat(authenticationRef.get()).isNull();
        assertThat(userIdRef.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(SecurityUtil.getCurrentUserId()).isNull();
    }

    private JwtAuthenticationFilter buildFilter() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtSecret", JWT_SECRET);
        return filter;
    }
}
