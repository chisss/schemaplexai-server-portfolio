package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 权限表实体
 */
@Data
@TableName("sf_permission")
public class Permission implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 权限名称 */
    private String name;

    /** 权限编码，如 agent:create */
    private String code;

    /** 类型: menu/button/data */
    private String type;

    /** 父权限ID */
    private String parentId;

    /** 关联API路径 */
    private String path;

    /** HTTP方法 */
    private String method;

    /** 描述 */
    private String description;

    /** 排序 */
    private Integer sortOrder;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
