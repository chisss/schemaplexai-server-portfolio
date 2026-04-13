package com.schemaplexai.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一返回码
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    // ========== 通用 ==========
    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未授权"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // ========== 认证授权 10000~10999 ==========
    // 与前端契约保持一致: 10001=Token过期, 10002=用户名或密码错误, 10003=RefreshToken无效
    TOKEN_EXPIRED(10001, "Token已过期"),
    LOGIN_FAILED(10002, "用户名或密码错误"),
    REFRESH_TOKEN_INVALID(10003, "Refresh Token 无效"),
    ACCOUNT_DISABLED(10004, "账号已被禁用"),
    ACCOUNT_NOT_FOUND(10005, "账号不存在"),
    NO_PERMISSION(10006, "没有操作权限"),
    PASSWORD_ERROR(10007, "密码错误"),

    // ========== Agent管理 20000~20999 ==========
    AGENT_NOT_FOUND(20001, "Agent不存在"),
    AGENT_BUSY(20002, "Agent正忙"),
    AGENT_INACTIVE(20003, "Agent未激活"),
    AGENT_CONFIG_ERROR(20004, "Agent配置错误"),
    AGENT_NAME_DUPLICATE(20005, "Agent名称已存在"),
    AGENT_CONFIG_INCOMPLETE(20006, "Agent配置未完成，不允许激活"),
    AGENT_TEAM_MEMBER_NOT_FOUND(20007, "团队成员不存在"),
    AGENT_CONTEXT_BINDING_NOT_FOUND(20008, "上下文绑定不存在"),

    // ========== Spec管理 30000~30999 ==========
    SPEC_NOT_FOUND(30001, "Spec不存在"),
    SPEC_STATUS_NOT_ALLOWED(30002, "当前状态不允许此操作"),
    SPEC_DOCUMENT_NOT_FOUND(30003, "Spec文档不存在"),
    SPEC_DOC_TYPE_INVALID(30004, "不支持的文档类型"),

    // ========== 上下文管理 40000~40999 ==========
    CONTEXT_NOT_FOUND(40001, "上下文不存在"),
    CONTEXT_ITEM_NOT_FOUND(40002, "上下文条目不存在"),
    CONTEXT_LEVEL_INVALID(40003, "无效的上下文层级"),
    CONTEXT_STATUS_NOT_ALLOWED(40004, "当前状态不允许操作"),
    CONTEXT_SNAPSHOT_NOT_FOUND(40005, "上下文快照不存在"),
    CONTEXT_NAME_DUPLICATE(40006, "上下文名称已存在"),

    // ========== 工作流 50000~50999 ==========
    WORKFLOW_NOT_FOUND(50001, "工作流模板不存在"),
    WORKFLOW_APPROVAL_TIMEOUT(50002, "审批超时"),
    WORKFLOW_INSTANCE_NOT_FOUND(50003, "工作流实例不存在"),
    WORKFLOW_DEFINITION_INVALID(50004, "工作流定义格式无效"),
    WORKFLOW_STATUS_NOT_ALLOWED(50005, "当前状态不允许此操作"),
    WORKFLOW_NODE_NOT_FOUND(50006, "节点执行记录不存在"),
    WORKFLOW_NOT_REVIEWER(50007, "非审批人无权操作"),
    REVIEW_SESSION_NOT_FOUND(50008, "评审会话不存在"),
    REVIEW_ALREADY_SUBMITTED(50009, "已提交评审不可重复"),
    WORKFLOW_TEMPLATE_IN_USE(50010, "模板被实例引用不可删除"),
    WORKFLOW_ENGINE_ERROR(50011, "工作流引擎异常"),
    WORKFLOW_NODE_TIMEOUT(50012, "节点执行超时"),
    WORKFLOW_PARALLEL_INCOMPLETE(50013, "并行分支未全部完成"),
    WORKFLOW_BUILTIN_READONLY(50014, "内置模板不可修改"),

    // ========== 系统配置 60000~60999 ==========
    CONFIG_NOT_FOUND(60001, "配置项不存在"),
    TENANT_NOT_FOUND(60002, "租户不存在"),
    ROLE_NOT_FOUND(60003, "角色不存在"),
    USER_NOT_FOUND(60004, "用户不存在"),
    USER_ALREADY_EXISTS(60005, "用户名已存在"),
    ROLE_CODE_DUPLICATE(60006, "角色编码已存在"),
    TENANT_CODE_DUPLICATE(60007, "租户编码已存在"),
    MODEL_NAME_DUPLICATE(60008, "模型名称已存在"),
    ROUTE_NOT_FOUND(60009, "路由规则不存在"),
    ROUTE_NAME_DUPLICATE(60010, "路由规则名称已存在"),
    TEMPLATE_NOT_FOUND(60011, "团队模板不存在"),
    MODEL_GROUP_NOT_FOUND(60012, "模型组不存在"),
    MODEL_GROUP_NAME_DUPLICATE(60013, "模型组名称已存在"),
    MODEL_GROUP_ALL_UNAVAILABLE(60014, "模型组内所有模型均不可用"),
    TENANT_TEMPLATE_INIT_FAILED(60015, "租户模板初始化失败"),
    TENANT_TEMPLATE_INIT_RUNNING(60016, "租户模板正在初始化，请勿重复触发"),

    // ========== 质量保障 70000~70999 ==========
    DEVIATION_DETECT_FAILED(70001, "偏离检测失败"),
    DEVIATION_NOT_FOUND(70002, "偏离记录不存在"),
    DEVIATION_STATUS_INVALID(70003, "偏离状态流转不合法"),
    INTENT_DEFECT_ANALYZE_FAILED(70004, "意图缺陷分析失败"),
    INTENT_DEFECT_NOT_FOUND(70005, "意图缺陷记录不存在"),
    CROSS_REVIEW_CREATE_FAILED(70006, "交叉审查创建失败"),
    CROSS_REVIEW_NOT_FOUND(70007, "交叉审查记录不存在"),
    AI_MODEL_TIMEOUT(70008, "AI模型调用超时"),
    SPEC_CONTENT_EMPTY(70009, "Spec文档内容为空，无法分析"),
    AGENT_EXECUTION_NOT_FOUND(70010, "Agent执行记录不存在"),

    // ========== 监控报表 80000~80999 ==========
    REPORT_GENERATE_FAILED(80001, "报表生成失败"),
    REPORT_TEMPLATE_NOT_FOUND(80002, "报表模板不存在"),
    EXPORT_FORMAT_NOT_SUPPORTED(80003, "导出格式不支持"),
    AUDIT_LOG_QUERY_FAILED(80004, "审计日志查询失败"),

    // ========== 成本管控 85000~85999 ==========
    BUDGET_NOT_FOUND(85001, "预算不存在"),
    BUDGET_CYCLE_CONFLICT(85002, "预算周期冲突"),
    BUDGET_AMOUNT_INVALID(85003, "预算金额不合法"),
    COST_DATA_QUERY_FAILED(85004, "成本数据查询失败"),

    // ========== 集成扩展 90000~90999 ==========
    INTEGRATION_CONNECT_FAILED(90001, "集成连接失败"),
    INTEGRATION_NOT_FOUND(90002, "集成配置不存在"),
    INTEGRATION_PROJECT_NOT_FOUND(90003, "集成项目关联不存在"),
    WEBHOOK_SIGNATURE_INVALID(90004, "Webhook签名验证失败"),
    WEBHOOK_EVENT_NOT_FOUND(90005, "Webhook事件不存在"),
    BRANCH_RULE_NOT_FOUND(90006, "分支规则不存在"),
    INTEGRATION_CONFIG_INVALID(90007, "集成配置信息无效"),
    INTEGRATION_SYNC_FAILED(90008, "集成同步失败"),

    // ---- Skill 90010~90019 ----
    SKILL_NOT_FOUND(90010, "技能不存在"),
    SKILL_NAME_DUPLICATE(90011, "技能名称已存在"),
    SKILL_VERSION_CONFLICT(90012, "技能版本冲突"),

    // ---- MCP Server 90020~90029 ----
    MCP_SERVER_NOT_FOUND(90020, "MCP Server不存在"),
    MCP_SERVER_NAME_DUPLICATE(90021, "MCP Server名称已存在"),
    MCP_SERVER_UNREACHABLE(90022, "MCP Server无法连接"),

    // ---- 通知渠道 90030~90039 ----
    NOTIFICATION_CHANNEL_NOT_FOUND(90030, "通知渠道不存在"),
    NOTIFICATION_CHANNEL_NAME_DUPLICATE(90031, "通知渠道名称已存在"),
    NOTIFICATION_CHANNEL_SEND_FAILED(90032, "消息发送失败"),
    MESSAGE_TEMPLATE_NOT_FOUND(90033, "消息模板不存在"),
    MESSAGE_TEMPLATE_NAME_DUPLICATE(90034, "消息模板名称已存在"),
    MESSAGE_TEMPLATE_VARIABLE_INVALID(90035, "消息模板变量不合法"),
    MESSAGE_TEMPLATE_TYPE_INVALID(90036, "消息模板类型不支持"),
    NOTIFICATION_RECORD_NOT_FOUND(90037, "通知记录不存在"),
    MESSAGE_TEMPLATE_CHANNEL_INVALID(90038, "消息模板不支持当前渠道"),

    // ---- 工作空间 90040~90049 ----
    WORKSPACE_NOT_FOUND(90040, "工作空间不存在"),
    WORKSPACE_CLONE_FAILED(90041, "项目克隆失败"),
    WORKSPACE_SYNC_FAILED(90042, "工作空间同步失败"),
    WORKSPACE_PATH_CONFLICT(90043, "工作空间路径冲突"),
    WORKSPACE_NAME_DUPLICATE(90044, "工作空间名称已存在"),
    ARTIFACT_NOT_FOUND(90045, "产物不存在"),

    // ---- CICD 90050~90059 ----
    CICD_PIPELINE_NOT_FOUND(90050, "Pipeline不存在"),
    CICD_TRIGGER_FAILED(90051, "构建触发失败"),
    CICD_PIPELINE_NAME_DUPLICATE(90052, "Pipeline名称已存在"),

    // ---- 安全合规 91060~91069 ----
    SECURITY_POLICY_NOT_FOUND(91060, "安全策略不存在"),
    SECURITY_POLICY_CODE_DUPLICATE(91061, "安全策略编码已存在"),
    SECURITY_AUDIT_EVENT_NOT_FOUND(91062, "安全审计事件不存在"),
    SECURITY_INCIDENT_NOT_FOUND(91063, "安全事件不存在"),
    SECURITY_RULE_PACK_NOT_FOUND(91064, "行业规则包不存在"),
    SECURITY_RULE_ITEM_NOT_FOUND(91065, "行业规则项不存在"),
    SECURITY_BINDING_NOT_FOUND(91066, "安全绑定关系不存在"),
    SECURITY_CHECK_BLOCKED(91067, "请求被安全策略阻断"),
    SECURITY_CHECK_PAUSED(91068, "请求因安全策略已暂停"),
    SECURITY_POLICY_VERSION_NOT_FOUND(91069, "安全策略版本不存在");

    private final int code;
    private final String message;
}
