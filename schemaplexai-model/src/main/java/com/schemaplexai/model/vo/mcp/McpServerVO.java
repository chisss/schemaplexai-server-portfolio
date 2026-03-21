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

    /** MCP Server URL */
    private String url;

    /** 认证类型 */
    private String authType;

    /** 脱敏后的认证配置 */
    private Map<String, Object> authConfig;

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
