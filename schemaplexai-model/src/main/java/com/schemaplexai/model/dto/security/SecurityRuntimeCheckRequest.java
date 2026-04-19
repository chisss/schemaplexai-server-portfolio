package com.schemaplexai.model.dto.security;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 安全运行时检查请求
 */
@Data
public class SecurityRuntimeCheckRequest {

    private String tenantId;

    private String scene;

    private String domainCode;

    private String resourceType;

    private String resourceId;

    private String resourceName;

    private String agentId;

    private String workflowInstanceId;

    private String workflowNodeId;

    private String workspaceId;

    private String traceId;

    private String content;

    private String toolCode;

    private Map<String, Object> arguments;

    private List<String> tags;

    private Map<String, Object> context;
}
