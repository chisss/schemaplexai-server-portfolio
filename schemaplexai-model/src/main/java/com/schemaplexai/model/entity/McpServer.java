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
 * MCP Server表实体
 */
@Data
@TableName(value = "sf_mcp_server", autoResultMap = true)
public class McpServer implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 名称 */
    private String name;

    /** MCP Server URL */
    private String url;

    /** 认证类型: none/api_key/oauth */
    private String authType;

    /** 认证配置(加密存储) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> authConfig;

    /** 发现的工具列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> tools;

    /** 状态: active/inactive */
    private String status;

    /** 最近健康检查时间 */
    private LocalDateTime lastHealthCheck;

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
