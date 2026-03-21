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

    /** MCP Server URL */
    @NotBlank(message = "URL不能为空")
    private String url;

    /** 认证类型: none/api_key/oauth */
    private String authType;

    /** 认证配置 */
    private Map<String, Object> authConfig;

    /** 描述 */
    private String description;
}
