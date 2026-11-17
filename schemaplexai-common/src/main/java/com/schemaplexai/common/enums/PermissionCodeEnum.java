package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 服务端强授权权限码枚举
 */
@Getter
@AllArgsConstructor
public enum PermissionCodeEnum {

    COST_VIEW("monitor:cost", "查看成本中心"),
    COST_BUDGET_MANAGE("cost:budget:manage", "管理成本预算"),
    SECURITY_VIEW("security:compliance:view", "查看安全合规中心"),
    SECURITY_POLICY_MANAGE("security:policy:manage", "管理安全策略"),
    AGENT_EXECUTE("agent:execute", "执行 Agent"),
    AGENT_MANAGE("agent:manage", "管理 Agent"),
    WORKFLOW_EXECUTE("workflow:execute", "执行工作流"),
    WORKFLOW_MANAGE("workflow:manage", "管理工作流"),
    APPROVAL_CENTER_VIEW("approval:center:view", "查看审批中心"),
    APPROVAL_DECIDE("approval:decide", "审批决策"),
    APPROVAL_ASSIGN("approval:center:assign", "审批指派"),
    APPROVAL_EXPORT("approval:center:export", "审批审计导出"),
    SYSTEM_TENANT_MANAGE("system:tenant:manage", "管理租户"),
    SYSTEM_CONFIG_MANAGE("system:config:manage", "管理系统配置");

    private final String code;
    private final String description;
}
