package com.schemaplexai.service.agent.tool.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 统一工具调用请求
 */
@Data
@Builder
public class ToolCall {

    private String callId;
    private String toolCode;
    private JsonNode arguments;
}
