package com.schemaplexai.web.filter;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
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
import java.util.stream.Collectors;

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
                try {
                    DecodedJWT jwt = JWT.require(Algorithm.HMAC256(jwtSecret))
                            .build()
                            .verify(token);

                    String userId = jwt.getSubject();
                    String tenantId = jwt.getClaim("tid").asString();
                    List<String> roles = jwt.getClaim("roles").asList(String.class);

                    // 设置安全上下文
                    SecurityUtil.setCurrentUserId(userId);
                    SecurityUtil.setCurrentTenantId(tenantId);

                    // 设置Spring Security认证信息
                    List<SimpleGrantedAuthority> authorities = roles != null
                            ? roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).collect(Collectors.toList())
                            : List.of();

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(userId, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch (JWTVerificationException e) {
                    // Token 无效（签名错误、过期等），不设置认证上下文，由 Spring Security 的授权检查拒绝
                    log.warn("JWT验证失败: {} - 请求路径: {}", e.getMessage(), request.getServletPath());
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
