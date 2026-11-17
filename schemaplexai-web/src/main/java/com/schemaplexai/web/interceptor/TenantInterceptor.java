package com.schemaplexai.web.interceptor;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.constant.SystemTenantConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 从请求头中提取租户信息
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final String[] PUBLIC_PATH_PREFIXES = {
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/health",
            "/actuator/health",
            "/i18n/",
            "/doc.html",
            "/swagger-resources",
            "/v3/api-docs",
            "/webjars",
            "/ws"
    };

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        if (isPublicPath(request)) {
            return true;
        }

        String jwtTenantId = SecurityUtil.getCurrentTenantId();
        String headerTenantId = normalizeTenantId(request.getHeader(SystemTenantConstant.TENANT_HEADER));

        if (isSuperAdmin() && StringUtils.hasText(headerTenantId)) {
            SecurityUtil.setCurrentTenantId(headerTenantId);
            return true;
        }

        if (StringUtils.hasText(headerTenantId)) {
            if (!StringUtils.hasText(jwtTenantId)) {
                throw new BusinessException(ResultCode.FORBIDDEN, SystemTenantConstant.TENANT_CONTEXT_MISSING_MESSAGE);
            }
            if (!headerTenantId.equals(jwtTenantId)) {
                throw new BusinessException(ResultCode.FORBIDDEN, "普通用户不允许切换租户");
            }
            return true;
        }

        if (!StringUtils.hasText(jwtTenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, SystemTenantConstant.TENANT_CONTEXT_MISSING_MESSAGE);
        }

        return true;
    }

    private boolean isSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .anyMatch(authority -> ("ROLE_" + CommonConstant.ROLE_SUPER_ADMIN).equalsIgnoreCase(authority));
    }

    private boolean isPublicPath(HttpServletRequest request) {
        String path = request.getServletPath();
        if (!StringUtils.hasText(path)) {
            path = request.getRequestURI();
        }
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeTenantId(String tenantId) {
        return StringUtils.hasText(tenantId) ? tenantId.trim() : null;
    }
}
