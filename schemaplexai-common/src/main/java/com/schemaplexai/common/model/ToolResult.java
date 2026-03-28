package com.schemaplexai.common.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * 统一工具执行结果
 */
@Data
@Builder
public class ToolResult {

    private String callId;
    private String toolCode;
    private boolean success;
    private JsonNode result;
    private String errorMessage;
}
