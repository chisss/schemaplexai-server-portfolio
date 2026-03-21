package com.schemaplexai.model.dto.system;

import lombok.Data;

import java.util.List;

/**
 * 更新角色请求DTO
 */
@Data
public class RoleUpdateRequest {

    /** 角色名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 状态: active/inactive */
    private String status;

    /** 关联权限ID列表 */
    private List<String> permissionIds;
}
