package com.schemaplexai.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求日志过滤器 - 记录请求耗时与traceId
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        org.slf4j.MDC.put("traceId", traceId);
        // Tomcat 线程池复用时不应残留中断标记；清理后可避免 Boot 类加载在异常分支被误伤
        boolean interrupted = Thread.interrupted();
        if (interrupted) {
            log.warn("[{}] 检测到请求线程残留中断标记，已在入口清理: {} {}",
                    traceId, request.getMethod(), request.getRequestURI());
        }

        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 请求结束时线程仍处于中断状态: {} {}",
                        traceId, request.getMethod(), request.getRequestURI());
            }
            log.info("[{}] {} {} → {} ({}ms)",
                    traceId, request.getMethod(), request.getRequestURI(),
                    response.getStatus(), duration);
            org.slf4j.MDC.clear();
        }
    }
}
