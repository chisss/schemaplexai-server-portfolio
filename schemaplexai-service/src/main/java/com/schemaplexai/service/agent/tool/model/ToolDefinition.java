package com.schemaplexai.service.agent.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 统一工具定义（Canonical Tool Schema）
 */
@Data
@Builder
public class ToolDefinition {

    private String code;
    private String name;
    private String description;
    private JsonNode inputSchema;
    private String sourceType;
    private boolean userVisible;
}
