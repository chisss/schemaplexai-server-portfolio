package com.schemaplexai.model.dto.mcp;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建MCP Server请求
 */
@Data
public class McpServerCreateRequest {

    /** 名称 */
    @NotBlank(message = "MCP Server名称不能为空")
    @Size(max = 100, message = "名称不能超过100个字符")
    private String name;

    /** 描述 */
    private String description;

    /** MCP Server URL */
    private String url;

    /** 传输类型: streamable_http / sse / stdio */
    private String transportType;

    /** 认证类型: none/api_key/oauth */
    private String authType;

    /** 认证配置 */
    private Map<String, Object> authConfig;

    /** 自定义请求头 */
    private Map<String, Object> headers;

    /** 服务类型: generic / database */
    private String serverType;

    /** 预置模板编码 */
    private String presetCode;

    /** 连接配置 */
    private Map<String, Object> connectionConfig;

    /** 传输配置 */
    private Map<String, Object> transportConfig;
}
