package com.schemaplexai.web.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 数据初始化器 — 应用启动时检查并创建预设的租户、角色和管理员账号
 * 仅在对应数据不存在时执行插入，幂等安全
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final TenantMapper tenantMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        log.info("===== 开始检查系统预设数据 =====");
        String tenantId = ensureDefaultTenant();
        String roleId = ensureSuperAdminRole(tenantId);
        String userId = ensureAdminUser(tenantId);
        ensureUserRoleMapping(userId, roleId);
        log.info("===== 系统预设数据检查完毕 =====");
    }

    /**
     * 确保默认租户存在
     */
    private String ensureDefaultTenant() {
        Tenant existing = tenantMapper.selectOne(
                new LambdaQueryWrapper<Tenant>().eq(Tenant::getCode, "default")
        );
        if (existing != null) {
            log.info("[租户] 默认租户已存在: id={}", existing.getId());
            return existing.getId();
        }

        Tenant tenant = new Tenant();
        tenant.setName("默认租户");
        tenant.setCode("default");
        tenant.setDescription("系统预设默认租户");
        tenant.setStatus("active");
        tenant.setCreatedAt(LocalDateTime.now());
        tenant.setUpdatedAt(LocalDateTime.now());
        tenantMapper.insert(tenant);
        log.info("[租户] 已创建默认租户: id={}", tenant.getId());
        return tenant.getId();
    }

    /**
     * 确保 SUPER_ADMIN 角色存在
     */
    private String ensureSuperAdminRole(String tenantId) {
        Role existing = roleMapper.selectOne(
                new LambdaQueryWrapper<Role>().eq(Role::getCode, "SUPER_ADMIN")
        );
        if (existing != null) {
            log.info("[角色] SUPER_ADMIN 角色已存在: id={}", existing.getId());
            return existing.getId();
        }

        Role role = new Role();
        role.setName("超级管理员");
        role.setCode("SUPER_ADMIN");
        role.setDescription("系统预设超级管理员角色，拥有全部权限");
        role.setIsSystem(true);
        role.setStatus("active");
        role.setTenantId(tenantId);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        log.info("[角色] 已创建 SUPER_ADMIN 角色: id={}", role.getId());
        return role.getId();
    }

    /**
     * 确保 admin 管理员用户存在（密码 BCrypt 加密）
     */
    private String ensureAdminUser(String tenantId) {
        User existing = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, "admin")
        );
        if (existing != null) {
            // 检查密码是否能匹配默认密码 admin123，不匹配则修正
            if (existing.getPasswordHash() == null
                    || !passwordEncoder.matches("admin123", existing.getPasswordHash())) {
                log.warn("[用户] admin 用户密码与默认密码不匹配，正在修正...");
                User update = new User();
                update.setId(existing.getId());
                update.setPasswordHash(passwordEncoder.encode("admin123"));
                userMapper.updateById(update);
                log.info("[用户] admin 用户密码已修正为默认密码 admin123");
            } else {
                log.info("[用户] admin 用户已存在且密码正确: id={}", existing.getId());
            }
            return existing.getId();
        }

        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername("admin");
        user.setPasswordHash(passwordEncoder.encode("admin123"));
        user.setEmail("admin@schemaplexai.com");
        user.setRealName("系统管理员");
        user.setStatus("active");
        user.setLoginType("password");
        user.setDeleted(0);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        log.info("[用户] 已创建 admin 用户: id={}, 默认密码: admin123", user.getId());
        return user.getId();
    }

    /**
     * 确保 admin 用户与 SUPER_ADMIN 角色的映射关系
     */
    private void ensureUserRoleMapping(String userId, String roleId) {
        UserRole existing = userRoleMapper.selectOne(
                new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .eq(UserRole::getRoleId, roleId)
        );
        if (existing != null) {
            log.info("[用户角色] admin <-> SUPER_ADMIN 映射已存在");
            return;
        }

        UserRole userRole = new UserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRoleMapper.insert(userRole);
        log.info("[用户角色] 已创建 admin <-> SUPER_ADMIN 映射");
    }
}
