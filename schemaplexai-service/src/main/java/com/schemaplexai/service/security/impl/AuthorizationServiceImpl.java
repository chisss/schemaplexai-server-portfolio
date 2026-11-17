package com.schemaplexai.service.security.impl;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.PermissionCodeEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.common.PermissionLoader;
import com.schemaplexai.service.security.AuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * 服务端权限校验服务实现
 */
@Slf4j
@Service("authorizationService")
@RequiredArgsConstructor
public class AuthorizationServiceImpl implements AuthorizationService {

    private static final Map<String, Set<String>> PERMISSION_ALIASES = Map.of(
            PermissionCodeEnum.AGENT_MANAGE.getCode(), Set.of("agent:create", "agent:update", "agent:delete", "agent:status", "agent:config"),
            PermissionCodeEnum.WORKFLOW_MANAGE.getCode(), Set.of("workflow:create", "workflow:update", "workflow:delete", "workflow:designer"),
            PermissionCodeEnum.APPROVAL_DECIDE.getCode(), Set.of("approval:center:review"),
            PermissionCodeEnum.SYSTEM_TENANT_MANAGE.getCode(), Set.of("system:tenant:create", "system:tenant:update", "system:tenant:delete", "system:tenant:status"),
            PermissionCodeEnum.SYSTEM_CONFIG_MANAGE.getCode(), Set.of("system:config:update")
    );

    private final PermissionLoader permissionLoader;

    @Override
    public boolean hasPermission(String permissionCode) {
        String userId = SecurityUtil.getCurrentUserId();
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(permissionCode)) {
            log.debug("权限校验拒绝: userId={}, permissionCode={}", userId, permissionCode);
            return false;
        }
        if (hasSuperAdminRole()) {
            return true;
        }

        Set<String> permissionCodes = permissionLoader.loadPermissionCodeSet(userId);
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return false;
        }
        return permissionCodes.contains(CommonConstant.PERMISSION_ALL)
                || permissionCodes.contains(permissionCode)
                || PERMISSION_ALIASES.getOrDefault(permissionCode, Set.of()).stream().anyMatch(permissionCodes::contains);
    }

    private boolean hasSuperAdminRole() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + CommonConstant.ROLE_SUPER_ADMIN).equalsIgnoreCase(authority.getAuthority()));
    }
}
