package com.schemaplexai.web.filter;

import com.schemaplexai.common.util.JwtUtil;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.web.interceptor.TenantInterceptor;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JwtAuthenticationFilterTest {

    private static final String JWT_SECRET = "unit-test-secret-key-at-least-32-chars";

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"REQUEST", "ASYNC"})
    void shouldAuthenticateAccessToken(DispatcherType dispatchType) throws Exception {
        JwtAuthenticationFilter filter = buildFilter();
        String accessToken = JwtUtil.generateAccessToken(
                "user-1",
                "tenant-1",
                List.of("admin", "developer"),
                JWT_SECRET,
                60_000
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspace/list");
        request.setDispatcherType(dispatchType);
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

    @ParameterizedTest
    @EnumSource(value = DispatcherType.class, names = {"REQUEST", "ASYNC"})
    void shouldRejectRefreshTokenAsAuthenticationCredential(DispatcherType dispatchType) throws Exception {
        JwtAuthenticationFilter filter = buildFilter();
        String refreshToken = JwtUtil.generateRefreshToken("user-1", JWT_SECRET, 60_000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/workspace/list");
        request.setDispatcherType(dispatchType);
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

    @Test
    void shouldCompleteEventStreamWithTenantValidationOnRedispatch() throws Exception {
        var endpoint = new TestEventStream();
        var mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addInterceptors(new TenantInterceptor())
                .addFilters(buildFilter())
                .build();
        String accessToken = JwtUtil.generateAccessToken("user-1", "tenant-1", List.of(), JWT_SECRET, 60_000);
        var result = mvc.perform(get("/execution/events").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(SecurityUtil.getCurrentTenantId()).isNull();

        endpoint.emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(SecurityUtil.getCurrentTenantId()).isNull();
        assertThat(endpoint.subscriptions).isEqualTo(1);
    }

    @RestController
    static class TestEventStream {
        final SseEmitter emitter = new SseEmitter();
        int subscriptions;

        @GetMapping(value = "/execution/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe() {
            subscriptions++;
            return emitter;
        }
    }

    private JwtAuthenticationFilter buildFilter() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        ReflectionTestUtils.setField(filter, "jwtSecret", JWT_SECRET);
        return filter;
    }
}
