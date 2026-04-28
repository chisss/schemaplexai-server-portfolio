package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ToolExecutionDecisionEnum;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 工具执行网关决策结果
 */
@Data
@Builder
public class ToolExecutionGateDecision {

    private ToolExecutionDecisionEnum decision;
    private String reason;
    private String approvalId;
    private Map<String, Object> payload;

    public boolean executable() {
        return decision == ToolExecutionDecisionEnum.EXECUTE;
    }
}
