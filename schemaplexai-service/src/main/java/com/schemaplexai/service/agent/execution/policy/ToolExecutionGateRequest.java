package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ToolIoTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Builder;
import lombok.Data;

/**
 * 工具执行网关请求
 */
@Data
@Builder
public class ToolExecutionGateRequest {

    private String tenantId;
    private String agentId;
    private String executionId;
    private String conversationId;
    private int round;
    private ExecutionModePolicy policy;
    private ToolExecutionRequest toolRequest;
    private ToolIoTypeEnum ioType;
    private boolean systemAgent;
}
