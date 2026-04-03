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
import java.util.List;
import java.util.Map;

/**
 * 技能表实体
 */
@Data
@TableName(value = "sf_skill", autoResultMap = true)
public class Skill implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID，NULL表示系统级 */
    private String tenantId;

    /** 技能名称 */
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 描述 */
    private String description;

    /** Skill 激活提示词 */
    private String skillPrompt;

    /** 版本 */
    private String version;

    /** 分类 */
    private String category;

    /** 参数定义 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> parameters;

    /** Skill 资源清单 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> resources;

    /** Skill 渐进式披露配置 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> exposureConfig;

    /** 实现方式 {type: script/mcp/api, content: ...} */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> implementation;

    /** 状态: active/inactive */
    private String status;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
