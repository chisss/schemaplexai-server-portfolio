package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色信息VO
 */
@Data
public class RoleVO {

    /** 角色ID */
    private String id;

    /** 角色名称 */
    private String name;

    /** 角色编码 */
    private String code;

    /** 描述 */
    private String description;

    /** 是否系统预设角色 */
    private Boolean isSystem;

    /** 状态: active/inactive */
    private String status;

    /** 关联权限列表 */
    private List<PermissionVO> permissions;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
