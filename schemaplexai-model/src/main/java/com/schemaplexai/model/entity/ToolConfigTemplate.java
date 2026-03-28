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
 * 工具配置模板定义
 */
@Data
@TableName(value = "sf_tool_config_template", autoResultMap = true)
public class ToolConfigTemplate implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /**
     * 工具编码，如 mcp.postgres / skill.excel
     */
    private String toolCode;

    /**
     * JSON Schema 配置定义
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> configSchema;

    /**
     * 是否内置模板
     */
    private Boolean isBuiltin;

    /**
     * 模板描述
     */
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
