package com.schemaplexai.model.vo.auth;

import lombok.Data;

import java.util.List;

/**
 * 登录成功响应VO
 */
@Data
public class LoginVO {

    /** 访问令牌 */
    private String accessToken;

    /** 刷新令牌 */
    private String refreshToken;

    /** 用户信息 */
    private UserInfoVO userInfo;

    @Data
    public static class UserInfoVO {
        /** 用户ID */
        private String id;
        /** 用户名 */
        private String username;
        /** 昵称 */
        private String nickname;
        /** 邮箱 */
        private String email;
        /** 头像 */
        private String avatar;
        /** 角色编码列表 */
        private List<String> roles;
        /** 权限编码列表 */
        private List<String> permissions;
        /** 租户ID */
        private String tenantId;
        /** 租户名称 */
        private String tenantName;
    }
}
