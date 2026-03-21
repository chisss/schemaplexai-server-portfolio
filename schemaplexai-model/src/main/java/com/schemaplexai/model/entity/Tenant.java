package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 租户表实体
 */
@Data
@TableName(value = "sf_tenant", autoResultMap = true)
public class Tenant implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户名称 */
    private String name;

    /** 租户编码 */
    private String code;

    /** 描述 */
    private String description;

    /** Logo地址 */
    private String logoUrl;

    /** 状态: active/inactive */
    private String status;

    /** 租户级配置（JSONB） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
