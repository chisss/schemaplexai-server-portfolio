package com.schemaplexai.service.agent.tool.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ToolExecutionResult {
    private String status;
    private Object output;
    private String error;
    private Map<String, Object> providerPayload;
    private Long latencyMs;
    private Map<String, Object> metadata;
}
