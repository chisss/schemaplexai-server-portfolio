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
    SYSTEM_CONFIG_MANAGE("system:config:manage", "管理系统配置"),
    // ===== 权限码补全战役新增（2026-11-18，方案见 docs/权限码补全方案-2026-11-18.md） =====
    SYSTEM_USER_MANAGE("system:user:manage", "管理用户"),
    SYSTEM_ROLE_MANAGE("system:role:manage", "管理角色"),
    SYSTEM_MENU_MANAGE("system:menu:manage", "管理菜单"),
    SYSTEM_DICT_MANAGE("system:dict:manage", "管理字典"),
    SYSTEM_I18N_MANAGE("system:i18n:manage", "管理国际化"),
    AI_MODEL_GROUP_MANAGE("ai:model-group:manage", "管理模型组"),
    AGENT_MEMBER_MANAGE("agent:member:manage", "管理 Team 成员绑定"),
    AGENT_MEMORY_MANAGE("agent:memory:manage", "管理 Agent 记忆"),
    SPEC_MANAGE("spec:manage", "管理 Spec"),
    SPEC_REVIEW("spec:review", "Spec 评审决策"),
    SPEC_TEMPLATE_MANAGE("spec:template:manage", "管理 Spec 模板"),
    CICD_MANAGE("cicd:manage", "管理 CICD 管道"),
    CONTEXT_MANAGE("context:manage", "管理上下文"),
    WORKSPACE_MANAGE("workspace:manage", "管理工作空间"),
    DATABASE_SOURCE_MANAGE("database:source:manage", "管理数据源"),
    INTEGRATION_MANAGE("integration:manage", "管理集成"),
    MCP_SERVER_MANAGE("mcp:server:manage", "管理 MCP Server"),
    API_GATEWAY_MANAGE("gateway:manage", "管理 API 网关"),
    GATEWAY_EXECUTE("gateway:execute", "网关转发调用"),
    QUALITY_MANAGE("quality:manage", "质量偏差/缺陷处理"),
    QUALITY_CONFIG_MANAGE("quality:config:manage", "管理质量配置"),
    EVALUATION_MANAGE("evaluation:manage", "管理评估数据集/任务"),
    REVIEW_MANAGE("review:manage", "管理评审会话"),
    REVIEW_DECIDE("review:decide", "评审决策"),
    MESSAGE_TEMPLATE_MANAGE("message:template:manage", "管理消息模板"),
    NOTIFICATION_CHANNEL_MANAGE("notification:channel:manage", "管理通知渠道"),
    MONITOR_MANAGE("monitor:manage", "管理监控报表"),
    MARKETPLACE_MANAGE("marketplace:manage", "插件安装/卸载"),
    KNOWLEDGE_MANAGE("knowledge:manage", "知识文档管理"),
    USER_MEMORY_MANAGE("memory:user:manage", "管理用户记忆"),
    SELF_SERVICE("self:service", "个人自助操作");

    private final String code;
    private final String description;
}
