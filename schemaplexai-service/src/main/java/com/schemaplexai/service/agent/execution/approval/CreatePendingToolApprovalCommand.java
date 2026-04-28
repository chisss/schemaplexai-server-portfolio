package com.schemaplexai.service.agent.execution.approval;

import com.schemaplexai.common.enums.ToolIoTypeEnum;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.Builder;
import lombok.Data;

/**
 * 创建待审批工具调用命令
 */
@Data
@Builder
public class CreatePendingToolApprovalCommand {

    private String tenantId;
    private String agentId;
    private String executionId;
    private String conversationId;
    private Integer roundNum;
    private String executionMode;
    private ToolIoTypeEnum ioType;
    private String riskLevel;
    private ToolExecutionRequest toolRequest;
}
