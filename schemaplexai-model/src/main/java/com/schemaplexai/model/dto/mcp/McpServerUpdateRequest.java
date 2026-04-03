package com.schemaplexai.model.dto.mcp;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新MCP Server请求
 */
@Data
public class McpServerUpdateRequest {

    /** 名称 */
    @Size(max = 100, message = "名称不能超过100个字符")
    private String name;

    /** 描述 */
    private String description;

    /** MCP Server URL */
    private String url;

    /** 传输类型 */
    private String transportType;

    /** 认证类型 */
    private String authType;

    /** 认证配置 */
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

    /** 状态: active/inactive */
    private String status;
}
