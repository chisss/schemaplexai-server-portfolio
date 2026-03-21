package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.util.List;

/**
 * 菜单信息VO（支持树形结构）
 */
@Data
public class MenuVO {

    /** 菜单ID */
    private String id;

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

    /** 菜单类型: menu/button */
    private String menuType;

    /** 子菜单列表（树形结构） */
    private List<MenuVO> children;
}
