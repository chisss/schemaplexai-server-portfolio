package com.schemaplexai.model.vo.agent;

import java.util.List;
import java.util.Map;

/** 执行详情中的可恢复工具审批视图。 */
public record AgentPendingApprovalVO(String approvalId, String toolCode, Map<String, Object> arguments,
                                     String ioType, String riskLevel, List<String> allowedDecisions) {
}
