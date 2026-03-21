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

    /** MCP Server URL */
    private String url;

    /** 认证类型 */
    private String authType;

    /** 认证配置 */
    private Map<String, Object> authConfig;

    /** 状态: active/inactive */
    private String status;
}
