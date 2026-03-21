package com.schemaplexai.web.interceptor;

import com.schemaplexai.common.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 从请求头中提取租户信息
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 如果JWT中已设置了tenantId，则跳过
        if (SecurityUtil.getCurrentTenantId() != null) {
            return true;
        }

        // 从请求头中获取租户ID
        String tenantId = request.getHeader(TENANT_HEADER);
        if (StringUtils.hasText(tenantId)) {
            SecurityUtil.setCurrentTenantId(tenantId);
        }

        return true;
    }
}
