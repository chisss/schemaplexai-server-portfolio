package com.schemaplexai.service.auth.assembler;

import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.entity.Tenant;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.auth.LoginVO;
import com.schemaplexai.model.vo.auth.TokenVO;
import com.schemaplexai.service.auth.handler.TokenHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 认证响应组装器 — 封装 LoginVO 等复杂响应对象的组装逻辑
 */
@Component
@RequiredArgsConstructor
public class AuthAssembler {

    private final TenantMapper tenantMapper;

    /**
     * 组装登录成功响应
     *
     * @param user        已验证的用户实体
     * @param tokenPair   生成的 Token 对
     * @param roleCodes   角色编码列表
     * @param permissions 权限编码列表
     * @return 完整的 LoginVO
     */
    public LoginVO assembleLoginVO(User user, TokenHandler.TokenPair tokenPair,
                                   List<String> roleCodes, List<String> permissions) {
        var tenantName = resolveTenantName(user.getTenantId());

        var userInfo = new LoginVO.UserInfoVO();
        userInfo.setId(user.getId());
        userInfo.setUsername(user.getUsername());
        userInfo.setNickname(user.getRealName());
        userInfo.setEmail(user.getEmail());
        userInfo.setAvatar(user.getAvatarUrl());
        userInfo.setRoles(roleCodes);
        userInfo.setPermissions(permissions);
        userInfo.setTenantId(user.getTenantId());
        userInfo.setTenantName(tenantName);

        var loginVO = new LoginVO();
        loginVO.setAccessToken(tokenPair.accessToken());
        loginVO.setRefreshToken(tokenPair.refreshToken());
        loginVO.setUserInfo(userInfo);
        return loginVO;
    }

    /**
     * 组装 Token 刷新响应
     */
    public TokenVO assembleTokenVO(String accessToken, String refreshToken) {
        return new TokenVO(accessToken, refreshToken);
    }

    /**
     * 查询租户名称
     */
    private String resolveTenantName(String tenantId) {
        if (tenantId == null) {
            return null;
        }
        Tenant tenant = tenantMapper.selectById(tenantId);
        return tenant != null ? tenant.getName() : null;
    }
}
