package com.schemaplexai.model.dto.database;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 数据库数据源创建/更新请求
 */
@Data
public class DatabaseSourceSaveRequest {

    /** 数据源名称 */
    @NotBlank(message = "数据源名称不能为空")
    @Size(max = 100, message = "数据源名称不能超过100个字符")
    private String name;

    /** 数据库类型 */
    @NotBlank(message = "数据库类型不能为空")
    private String databaseType;

    /** 描述 */
    private String description;

    /** 接入模式: preset / custom */
    private String connectionMode;

    /** 预设编码 */
    private String presetCode;

    /** MCP 传输类型 */
    private String transportType;

    /** MCP URL */
    private String url;

    /** MCP 鉴权类型 */
    private String authType;

    /** MCP 鉴权配置 */
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

    /** 密码 */
    private String password;

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

    /** STDIO 命令 */
    private List<String> command;

    /** 环境变量 */
    private Map<String, String> environment;
}
