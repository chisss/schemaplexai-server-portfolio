package com.schemaplexai.service.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.PermissionMapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.RolePermissionMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.model.entity.Permission;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.entity.RolePermission;
import com.schemaplexai.model.entity.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限加载器 — 封装 User → UserRole → Role → RolePermission → Permission 的链式加载逻辑
 * <p>
 * 被 Auth 模块和 Menu 模块共同使用，消除两处重复的权限查询链。
 */
@Component
@RequiredArgsConstructor
public class PermissionLoader {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    /**
     * 加载用户的角色编码列表
     */
    public List<String> loadRoleCodes(String userId) {
        var roles = loadRoles(userId);
        return roles.stream().map(Role::getCode).toList();
    }

    /**
     * 加载用户的角色ID列表
     */
    public List<String> loadRoleIds(String userId) {
        var userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)
        );
        return userRoles.stream().map(UserRole::getRoleId).toList();
    }

    /**
     * 加载用户的权限编码列表（SUPER_ADMIN 返回 ["*"]）
     */
    public List<String> loadPermissionCodes(String userId, List<String> roleCodes) {
        if (roleCodes.contains("SUPER_ADMIN")) {
            return List.of("*");
        }

        var roleIds = loadRoleIds(userId);
        if (roleIds.isEmpty()) {
            return Collections.emptyList();
        }

        return loadPermissionCodesByRoleIds(roleIds);
    }

    /**
     * 加载用户的权限编码集合（用于菜单过滤）
     * SUPER_ADMIN 返回包含 "*" 的集合，表示拥有全部权限
     */
    public Set<String> loadPermissionCodeSet(String userId) {
        var roles = loadRoles(userId);
        boolean isSuperAdmin = roles.stream().anyMatch(r -> "SUPER_ADMIN".equals(r.getCode()));
        if (isSuperAdmin) {
            return Set.of("*");
        }

        var roleIds = roles.stream().map(Role::getId).toList();
        if (roleIds.isEmpty()) {
            return Collections.emptySet();
        }

        var rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds)
        );
        if (rolePerms.isEmpty()) {
            return Collections.emptySet();
        }

        var permIds = rolePerms.stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .toList();
        var permissions = permissionMapper.selectBatchIds(permIds);
        return permissions.stream()
                .map(Permission::getCode)
                .collect(Collectors.toSet());
    }

    // ==================== 内部方法 ====================

    private List<Role> loadRoles(String userId) {
        var userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)
        );
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        var roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds);
    }

    private List<String> loadPermissionCodesByRoleIds(List<String> roleIds) {
        var rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().in(RolePermission::getRoleId, roleIds)
        );
        if (rolePerms.isEmpty()) {
            return Collections.emptyList();
        }

        var permIds = rolePerms.stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .toList();
        var permissions = permissionMapper.selectBatchIds(permIds);
        return permissions.stream().map(Permission::getCode).distinct().toList();
    }
}
