package com.schemaplexai.model.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 更新用户请求DTO
 */
@Data
public class UserUpdateRequest {

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 真实姓名 */
    private String realName;

    /** 头像地址 */
    private String avatarUrl;

    /** 状态: active/inactive/locked */
    private String status;

    /** 角色ID列表 */
    private List<String> roleIds;
}
