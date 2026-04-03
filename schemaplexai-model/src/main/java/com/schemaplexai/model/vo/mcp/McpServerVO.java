package com.schemaplexai.model.vo.mcp;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * MCP Server视图对象
 */
@Data
public class McpServerVO {

    /** 主键ID */
    private String id;

    /** 名称 */
    private String name;

    /** 描述 */
    private String description;

    /** MCP Server URL */
    private String url;

    /** 传输类型 */
    private String transportType;

    /** 认证类型 */
    private String authType;

    /** 脱敏后的认证配置 */
    private Map<String, Object> authConfig;

    /** 自定义请求头 */
    private Map<String, Object> headers;

    /** 服务类型 */
    private String serverType;

    /** 预置模板编码 */
    private String presetCode;

    /** 连接配置 */
    private Map<String, Object> connectionConfig;

    /** 传输配置 */
    private Map<String, Object> transportConfig;

    /** 发现的工具列表 */
    private List<Object> tools;

    /** 状态 */
    private String status;

    /** 最近健康检查时间 */
    private LocalDateTime lastHealthCheck;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 创建人名称 */
    private String createdByName;
}
