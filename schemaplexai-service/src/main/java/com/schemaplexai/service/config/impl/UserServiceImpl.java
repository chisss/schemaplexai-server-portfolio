package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.converter.RoleConverter;
import com.schemaplexai.model.converter.UserConverter;
import com.schemaplexai.model.dto.system.UserCreateRequest;
import com.schemaplexai.model.dto.system.UserQueryRequest;
import com.schemaplexai.model.dto.system.UserUpdateRequest;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.system.UserVO;
import com.schemaplexai.service.common.AssociationManager;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final UserConverter userConverter;
    private final RoleConverter roleConverter;
    private final AssociationManager associationManager;
    private final EntityValidator entityValidator;
    private final PasswordEncoder passwordEncoder;

    @Override
    public PageResult<UserVO> listUsers(UserQueryRequest request) {
        var page = new Page<User>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<User>();

        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(User::getUsername, request.getKeyword())
                    .or()
                    .like(User::getRealName, request.getKeyword())
            );
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(User::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(User::getCreatedAt);

        var result = userMapper.selectPage(page, wrapper);
        var voList = result.getRecords().stream()
                .map(this::enrichWithRoles)
                .toList();
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public UserVO getUserById(String id) {
        var user = entityValidator.requireExists(userMapper, id, ResultCode.USER_NOT_FOUND);
        return enrichWithRoles(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO createUser(UserCreateRequest request) {
        entityValidator.checkUnique(userMapper, User::getUsername, request.getUsername(), ResultCode.USER_ALREADY_EXISTS);

        var user = userConverter.fromCreateRequest(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userMapper.insert(user);

        associationManager.saveUserRoles(user.getId(), request.getRoleIds());

        log.info("创建用户成功: userId={}, username={}", user.getId(), user.getUsername());
        return enrichWithRoles(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateUser(String id, UserUpdateRequest request) {
        entityValidator.requireExists(userMapper, id, ResultCode.USER_NOT_FOUND);

        var updateEntity = new User();
        updateEntity.setId(id);
        updateEntity.setEmail(request.getEmail());
        updateEntity.setPhone(request.getPhone());
        updateEntity.setRealName(request.getRealName());
        updateEntity.setAvatarUrl(request.getAvatarUrl());
        updateEntity.setStatus(request.getStatus());
        userMapper.updateById(updateEntity);

        if (request.getRoleIds() != null) {
            associationManager.replaceUserRoles(id, request.getRoleIds());
        }

        log.info("更新用户成功: userId={}", id);
        return getUserById(id);
    }

    @Override
    public void deleteUser(String id) {
        entityValidator.requireExists(userMapper, id, ResultCode.USER_NOT_FOUND);
        userMapper.deleteById(id);
        log.info("删除用户成功: userId={}", id);
    }

    @Override
    public void resetPassword(String id, String newPassword) {
        entityValidator.requireExists(userMapper, id, ResultCode.USER_NOT_FOUND);

        var updateEntity = new User();
        updateEntity.setId(id);
        updateEntity.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(updateEntity);
        log.info("重置用户密码成功: userId={}", id);
    }

    /**
     * 为 UserVO 附加关联角色列表
     */
    private UserVO enrichWithRoles(User user) {
        var vo = userConverter.toVO(user);
        var roles = associationManager.loadUserRoles(user.getId());
        vo.setRoles(roleConverter.toVOList(roles));
        return vo;
    }
}
