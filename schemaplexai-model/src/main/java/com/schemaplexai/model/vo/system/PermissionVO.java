package com.schemaplexai.model.vo.system;

import lombok.Data;

/**
 * 权限信息VO
 */
@Data
public class PermissionVO {

    /** 权限ID */
    private String id;

    /** 权限名称 */
    private String name;

    /** 权限编码 */
    private String code;

    /** 类型: menu/button/data */
    private String type;

    /** 父权限ID */
    private String parentId;

    /** 关联API路径 */
    private String path;

    /** HTTP方法 */
    private String method;

    /** 排序 */
    private Integer sortOrder;
}
