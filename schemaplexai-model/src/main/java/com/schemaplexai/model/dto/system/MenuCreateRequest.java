package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建菜单请求DTO
 */
@Data
public class MenuCreateRequest {

    /** 父菜单ID */
    private String parentId;

    /** 菜单名称 */
    @NotBlank(message = "菜单名称不能为空")
    private String name;

    /** 前端路由路径 */
    private String path;

    /** 前端组件路径 */
    private String component;

    /** 图标名称 */
    private String icon;

    /** 排序 */
    private Integer sortOrder;

    /** 是否可见，默认true */
    private Boolean visible = true;

    /** 关联权限编码 */
    private String permissionCode;

    /** 菜单类型: menu/button */
    @NotBlank(message = "菜单类型不能为空")
    private String menuType;
}
