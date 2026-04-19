package com.schemaplexai.web.interceptor;

import com.schemaplexai.common.constant.CommonConstant;
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

    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        String jwtTenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(jwtTenantId)) {
            return true;
        }

        String headerTenantId = request.getHeader(TENANT_HEADER);
        if (StringUtils.hasText(headerTenantId)) {
            if (!isSuperAdmin()) {
                throw new BusinessException(ResultCode.FORBIDDEN, "非超管用户不允许通过 Header 指定租户");
            }
            SecurityUtil.setCurrentTenantId(headerTenantId.trim());
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
}
