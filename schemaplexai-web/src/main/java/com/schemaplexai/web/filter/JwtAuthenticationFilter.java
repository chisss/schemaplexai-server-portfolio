package com.schemaplexai.web.filter;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.schemaplexai.common.util.JwtUtil;
import com.schemaplexai.common.util.SecurityUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT认证过滤器
 * 从请求头中提取并验证JWT Token，设置Spring Security认证上下文
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    @Value("${jwt.secret:default-secret-key-at-least-32-characters}")
    private String jwtSecret;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = extractToken(request);
            if (token != null) {
                DecodedJWT jwt = JwtUtil.verifyToken(token, jwtSecret);
                if (jwt == null) {
                    log.warn("JWT验证失败 - 请求路径: {}", request.getServletPath());
                } else if (JwtUtil.isRefreshToken(jwt)) {
                    log.warn("检测到 RefreshToken 访问受保护接口，已拒绝认证: path={}", request.getServletPath());
                } else {
                    bindAuthentication(jwt);
                }
            }

            filterChain.doFilter(request, response);
        } finally {
            SecurityUtil.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void bindAuthentication(DecodedJWT jwt) {
        String userId = JwtUtil.getUserId(jwt);
        String tenantId = JwtUtil.getTenantId(jwt);
        List<String> roles = JwtUtil.getRoles(jwt);
        if (!StringUtils.hasText(userId)) {
            return;
        }

        SecurityUtil.setCurrentUserId(userId);
        SecurityUtil.setCurrentTenantId(tenantId);

        List<SimpleGrantedAuthority> authorities = roles != null
                ? roles.stream().filter(StringUtils::hasText).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()
                : List.of();
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/auth/login")
                || path.startsWith("/auth/register")
                || path.startsWith("/auth/refresh")
                || path.startsWith("/i18n/")
                || path.startsWith("/doc.html")
                || path.startsWith("/swagger-resources")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars")
                || path.startsWith("/ws");
    }
}
