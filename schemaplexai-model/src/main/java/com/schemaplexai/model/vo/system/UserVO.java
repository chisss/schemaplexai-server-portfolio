package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户信息VO
 */
@Data
public class UserVO {

    /** 用户ID */
    private String id;

    /** 用户名 */
    private String username;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 头像地址 */
    private String avatarUrl;

    /** 状态: active/inactive/locked */
    private String status;

    /** 登录类型: password/sso/oauth */
    private String loginType;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;

    /** 关联角色列表 */
    private List<RoleVO> roles;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
