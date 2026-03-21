package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 创建角色请求DTO
 */
@Data
public class RoleCreateRequest {

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    private String name;

    /** 角色编码 */
    @NotBlank(message = "角色编码不能为空")
    private String code;

    /** 描述 */
    private String description;

    /** 关联权限ID列表 */
    private List<String> permissionIds;
}
