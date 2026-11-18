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

import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 租户拦截器 - 从请求头中提取租户信息
 */
@Slf4j
@Component
public class TenantInterceptor implements HandlerInterceptor {

    /** 精确匹配的公开路径（不匹配同名前缀端点，如 /health 不匹配 /health-xxx） */
    private static final Set<String> EXACT_PUBLIC_PATHS = Set.of(
            "/auth/login",
            "/auth/register",
            "/auth/refresh",
            "/health",
            "/actuator/health",
            "/doc.html"
    );

    /** 前缀匹配的公开路径（以 '/' 结尾，路径段边界语义，如 /ws/ 不匹配 /wsocket） */
    private static final String[] PUBLIC_PATH_PREFIXES = {
            "/i18n/",
            "/swagger-resources/",
            "/v3/api-docs/",
            "/webjars/",
            "/ws/"
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
        // 精确匹配优先（/health 不匹配 /health-check）
        if (EXACT_PUBLIC_PATHS.contains(path)) {
            return true;
        }
        // 前缀清单均以 '/' 结尾，仅匹配完整路径段边界（/ws/ 不匹配 /wsocket）
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
