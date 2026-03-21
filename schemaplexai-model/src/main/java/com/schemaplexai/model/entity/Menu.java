package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 菜单表实体
 */
@Data
@TableName("sf_menu")
public class Menu implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 父菜单ID */
    private String parentId;

    /** 菜单名称 */
    private String name;

    /** 前端路由路径 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 图标名称 */
    private String icon;

    /** 排序 */
    private Integer sortOrder;

    /** 是否可见 */
    private Boolean visible;

    /** 关联权限编码 */
    private String permissionCode;

    /** 菜单类型: dir/menu/button */
    private String menuType;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
