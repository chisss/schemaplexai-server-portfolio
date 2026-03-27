package com.schemaplexai.service.auth.validator;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 认证校验器 — 封装登录凭据校验和用户状态验证逻辑
 */
@Component
@RequiredArgsConstructor
public class AuthValidator {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    /**
     * 校验登录凭据：查询用户 → 密码比对 → 状态检查
     *
     * @param username 用户名
     * @param password 明文密码
     * @return 通过校验的 User 实体
     * @throws BusinessException 用户不存在、密码错误或账号被禁用时抛出
     */
    public User validateCredentials(String username, String password) {
        var user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ResultCode.LOGIN_FAILED);
        }

        validateUserActive(user);
        return user;
    }

    /**
     * 校验用户状态是否为 active
     */
    public void validateUserActive(User user) {
        if (!CommonConstant.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
    }
}
