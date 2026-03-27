package com.schemaplexai.service.auth.impl;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.dto.auth.LoginRequest;
import com.schemaplexai.model.dto.auth.RefreshTokenRequest;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.auth.LoginVO;
import com.schemaplexai.model.vo.auth.TokenVO;
import com.schemaplexai.model.vo.auth.WsTicketVO;
import com.schemaplexai.service.auth.assembler.AuthAssembler;
import com.schemaplexai.service.auth.AuthService;
import com.schemaplexai.service.auth.validator.AuthValidator;
import com.schemaplexai.service.auth.handler.TokenHandler;
import com.schemaplexai.service.common.PermissionLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 认证授权服务实现 — 纯编排器，业务逻辑委托给各职责组件
 * <p>
 * 职责划分：
 * <ul>
 *   <li>{@link AuthValidator} — 凭据校验和状态验证</li>
 *   <li>{@link TokenHandler} — JWT Token 的生成/存储/验证/撤销</li>
 *   <li>{@link PermissionLoader} — 角色权限链式加载</li>
 *   <li>{@link AuthAssembler} — 复杂 VO 对象的组装</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthValidator authValidator;
    private final TokenHandler tokenHandler;
    private final PermissionLoader permissionLoader;
    private final AuthAssembler authAssembler;
    private final UserMapper userMapper;

    @Override
    public LoginVO login(LoginRequest request) {
        // 1. 校验凭据（用户名 + 密码 + 账号状态）
        var user = authValidator.validateCredentials(request.getUsername(), request.getPassword());

        // 2. 加载角色和权限
        var roleCodes = permissionLoader.loadRoleCodes(user.getId());
        var permissions = permissionLoader.loadPermissionCodes(user.getId(), roleCodes);

        // 3. 生成 Token 并存储到 Redis
        var tokenPair = tokenHandler.generateAndStoreTokenPair(user.getId(), user.getTenantId(), roleCodes);

        // 4. 更新最后登录时间
        updateLastLoginTime(user.getId());

        // 5. 组装响应
        log.info("用户登录成功: userId={}, username={}", user.getId(), user.getUsername());
        return authAssembler.assembleLoginVO(user, tokenPair, roleCodes, permissions);
    }

    @Override
    public TokenVO refreshToken(RefreshTokenRequest request) {
        // 1. 验证 RefreshToken（签名 + Redis 比对）
        var userId = tokenHandler.validateRefreshToken(request.getRefreshToken());

        // 2. 校验用户状态
        var user = userMapper.selectById(userId);
        if (user == null || !CommonConstant.STATUS_ACTIVE.equals(user.getStatus())) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        // 3. 重新加载角色并生成新的 AccessToken
        var roleCodes = permissionLoader.loadRoleCodes(userId);
        var newAccessToken = tokenHandler.generateAccessToken(userId, user.getTenantId(), roleCodes);

        log.info("Token刷新成功: userId={}", userId);
        return authAssembler.assembleTokenVO(newAccessToken, request.getRefreshToken());
    }

    @Override
    public void logout(String userId) {
        tokenHandler.revokeRefreshToken(userId);
        log.info("用户登出: userId={}", userId);
    }

    @Override
    public WsTicketVO issueWsTicket(String userId, String tenantId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录或租户上下文缺失");
        }
        String wsTicket = tokenHandler.generateWsTicket(userId, tenantId);
        return new WsTicketVO(wsTicket);
    }

    private void updateLastLoginTime(String userId) {
        var updateUser = new User();
        updateUser.setId(userId);
        updateUser.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(updateUser);
    }
}
