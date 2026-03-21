package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建用户请求DTO
 */
@Data
public class UserCreateRequest {

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度6-32位")
    private String password;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 真实姓名 */
    private String realName;

    /** 头像地址 */
    private String avatarUrl;

    /** 角色ID列表 */
    private List<String> roleIds;
}
