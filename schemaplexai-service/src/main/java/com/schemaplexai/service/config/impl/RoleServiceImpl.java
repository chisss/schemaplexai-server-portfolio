package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.model.converter.PermissionConverter;
import com.schemaplexai.model.converter.RoleConverter;
import com.schemaplexai.model.dto.system.RoleCreateRequest;
import com.schemaplexai.model.dto.system.RoleQueryRequest;
import com.schemaplexai.model.dto.system.RoleUpdateRequest;
import com.schemaplexai.model.entity.Role;
import com.schemaplexai.model.vo.system.RoleVO;
import com.schemaplexai.service.common.AssociationManager;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 角色管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RoleConverter roleConverter;
    private final PermissionConverter permissionConverter;
    private final AssociationManager associationManager;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<RoleVO> listRoles(RoleQueryRequest request) {
        var page = new Page<Role>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Role>();

        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(Role::getName, request.getKeyword())
                    .or()
                    .like(Role::getCode, request.getKeyword())
            );
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(Role::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(Role::getCreatedAt);

        var result = roleMapper.selectPage(page, wrapper);
        var voList = roleConverter.toVOList(result.getRecords());

        // 批量加载权限，卡片视图需要展示权限数量
        voList.forEach(vo -> {
            var permissions = associationManager.loadRolePermissions(vo.getId());
            vo.setPermissions(permissionConverter.toVOList(permissions));
        });

        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<RoleVO> listAllRoles() {
        var wrapper = new LambdaQueryWrapper<Role>()
                .eq(Role::getStatus, CommonConstant.STATUS_ACTIVE)
                .orderByAsc(Role::getCreatedAt);
        var roles = roleMapper.selectList(wrapper);
        return roleConverter.toVOList(roles);
    }

    @Override
    public RoleVO getRoleById(String id) {
        var role = entityValidator.requireExists(roleMapper, id, ResultCode.ROLE_NOT_FOUND);
        var vo = roleConverter.toVO(role);

        // 详情视图：附加权限列表
        var permissions = associationManager.loadRolePermissions(id);
        vo.setPermissions(permissionConverter.toVOList(permissions));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO createRole(RoleCreateRequest request) {
        entityValidator.checkUnique(roleMapper, Role::getCode, request.getCode(), ResultCode.ROLE_CODE_DUPLICATE);

        var role = roleConverter.fromCreateRequest(request);
        roleMapper.insert(role);

        associationManager.saveRolePermissions(role.getId(), request.getPermissionIds());

        log.info("创建角色成功: roleId={}, code={}", role.getId(), role.getCode());
        return getRoleById(role.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public RoleVO updateRole(String id, RoleUpdateRequest request) {
        entityValidator.requireExists(roleMapper, id, ResultCode.ROLE_NOT_FOUND);

        var updateEntity = new Role();
        updateEntity.setId(id);
        updateEntity.setName(request.getName());
        updateEntity.setDescription(request.getDescription());
        updateEntity.setStatus(request.getStatus());
        roleMapper.updateById(updateEntity);

        if (request.getPermissionIds() != null) {
            associationManager.replaceRolePermissions(id, request.getPermissionIds());
        }

        log.info("更新角色成功: roleId={}", id);
        return getRoleById(id);
    }

    @Override
    public void deleteRole(String id) {
        var role = entityValidator.requireExists(roleMapper, id, ResultCode.ROLE_NOT_FOUND);

        if (Boolean.TRUE.equals(role.getIsSystem())) {
            throw new BusinessException(ResultCode.FAIL, "系统预设角色不允许删除");
        }

        roleMapper.deleteById(id);
        associationManager.deleteRolePermissions(id);
        log.info("删除角色成功: roleId={}", id);
    }

    @Override
    public void updateStatus(String id, String status) {
        if (!Set.of(CommonConstant.STATUS_ACTIVE, CommonConstant.STATUS_INACTIVE).contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的状态值，允许值: active, inactive");
        }
        entityValidator.requireExists(roleMapper, id, ResultCode.ROLE_NOT_FOUND);
        var update = new Role();
        update.setId(id);
        update.setStatus(status);
        roleMapper.updateById(update);
        log.info("更新角色状态: roleId={}, status={}", id, status);
    }
}
