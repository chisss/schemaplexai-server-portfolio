package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色表实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_role")
public class Role extends BaseEntity {

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
}
