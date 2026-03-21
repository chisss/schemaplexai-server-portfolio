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

/**
 * 关联关系管理器 — 统一管理 UserRole / RolePermission 关联表的 CRUD 操作
 */
@Component
@RequiredArgsConstructor
public class AssociationManager {

    private final UserRoleMapper userRoleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;

    // ==================== 用户-角色 ====================

    /**
     * 批量保存用户角色关联
     */
    public void saveUserRoles(String userId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        roleIds.stream()
                .map(roleId -> {
                    var ur = new UserRole();
                    ur.setUserId(userId);
                    ur.setRoleId(roleId);
                    return ur;
                })
                .forEach(userRoleMapper::insert);
    }

    /**
     * 替换用户角色关联（先删后插）
     */
    public void replaceUserRoles(String userId, List<String> roleIds) {
        userRoleMapper.delete(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)
        );
        saveUserRoles(userId, roleIds);
    }

    /**
     * 加载用户关联的角色列表
     */
    public List<Role> loadUserRoles(String userId) {
        var userRoles = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)
        );
        if (userRoles.isEmpty()) {
            return Collections.emptyList();
        }
        var roleIds = userRoles.stream().map(UserRole::getRoleId).toList();
        return roleMapper.selectBatchIds(roleIds);
    }

    // ==================== 角色-权限 ====================

    /**
     * 批量保存角色权限关联
     */
    public void saveRolePermissions(String roleId, List<String> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        permissionIds.stream()
                .map(permId -> {
                    var rp = new RolePermission();
                    rp.setRoleId(roleId);
                    rp.setPermissionId(permId);
                    return rp;
                })
                .forEach(rolePermissionMapper::insert);
    }

    /**
     * 替换角色权限关联（先删后插）
     */
    public void replaceRolePermissions(String roleId, List<String> permissionIds) {
        deleteRolePermissions(roleId);
        saveRolePermissions(roleId, permissionIds);
    }

    /**
     * 清理角色关联的所有权限
     */
    public void deleteRolePermissions(String roleId) {
        rolePermissionMapper.delete(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId)
        );
    }

    /**
     * 加载角色关联的权限列表
     */
    public List<Permission> loadRolePermissions(String roleId) {
        var rolePerms = rolePermissionMapper.selectList(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId)
        );
        if (rolePerms.isEmpty()) {
            return Collections.emptyList();
        }
        var permIds = rolePerms.stream().map(RolePermission::getPermissionId).toList();
        return permissionMapper.selectBatchIds(permIds);
    }
}
