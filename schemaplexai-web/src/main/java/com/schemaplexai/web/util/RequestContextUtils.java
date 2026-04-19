package com.schemaplexai.web.util;

import com.schemaplexai.model.dto.security.SecurityAuditContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.experimental.UtilityClass;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * HTTP 请求上下文工具：统一解析客户端 IP / UA / traceId，
 * 并构造 {@link SecurityAuditContext}。
 */
@UtilityClass
public class RequestContextUtils {

    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_X_REAL_IP = "X-Real-IP";
    private static final String HEADER_USER_AGENT = "User-Agent";

    /**
     * 解析客户端真实 IP：优先 X-Forwarded-For 首段，其次 X-Real-IP，最后 remoteAddr。
     */
    public static String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    public static String resolveUserAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader(HEADER_USER_AGENT);
    }

    /**
     * 构造安全审计上下文；request 为空时返回全空上下文。
     */
    public static SecurityAuditContext buildSecurityAuditContext(HttpServletRequest request) {
        if (request == null) {
            return new SecurityAuditContext(null, null);
        }
        return new SecurityAuditContext(resolveClientIp(request), resolveUserAgent(request));
    }

    /**
     * 尝试从 {@link RequestContextHolder} 获取当前线程的 HttpServletRequest。
     * 异步线程通常取不到，调用方需处理空值。
     */
    public static HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sa) {
                return sa.getRequest();
            }
        } catch (IllegalStateException ignored) {
            // 非请求线程，忽略
        }
        return null;
    }

    /**
     * 获取当前 MDC 中的 traceId；不存在返回 null。
     */
    public static String currentTraceId() {
        return MDC.get("traceId");
    }
}
