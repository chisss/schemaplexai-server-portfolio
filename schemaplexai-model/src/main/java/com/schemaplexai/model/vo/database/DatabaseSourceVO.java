package com.schemaplexai.model.vo.database;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 数据库数据源视图
 */
@Data
public class DatabaseSourceVO {

    /** 主键 */
    private String id;

    /** 名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 数据库类型 */
    private String databaseType;

    /** 接入模式 */
    private String connectionMode;

    /** 预设编码 */
    private String presetCode;

    /** MCP URL */
    private String url;

    /** 传输类型 */
    private String transportType;

    /** 鉴权类型 */
    private String authType;

    /** 脱敏后的鉴权配置 */
    private Map<String, Object> authConfig;

    /** 自定义请求头 */
    private Map<String, Object> headers;

    /** Host */
    private String host;

    /** Port */
    private String port;

    /** 数据库名 */
    private String database;

    /** 默认 schema */
    private String schema;

    /** 用户名 */
    private String username;

    /** 是否存在密码 */
    private Boolean passwordConfigured;

    /** 连接 URI */
    private String connectionUri;

    /** SSL 模式 */
    private String sslMode;

    /** 是否只读 */
    private Boolean readOnly;

    /** 查询工具名 */
    private String queryToolName;

    /** 标签 */
    private List<String> tags;

    /** 命令 */
    private List<String> command;

    /** 环境变量 */
    private Map<String, String> environment;

    /** 连接配置 */
    private Map<String, Object> connectionConfig;

    /** 传输配置 */
    private Map<String, Object> transportConfig;

    /** 工具列表 */
    private List<Map<String, Object>> tools;

    /** 工具数量 */
    private Integer toolCount;

    /** 状态 */
    private String status;

    /** 最近健康检查时间 */
    private LocalDateTime lastHealthCheck;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}
