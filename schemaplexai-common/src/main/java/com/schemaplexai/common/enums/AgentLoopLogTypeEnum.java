package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 执行循环日志类型枚举
 * <p>对应 AgentExecutionEngine 中各类关键日志事件的类型标识</p>
 */
@Getter
@AllArgsConstructor
public enum AgentLoopLogTypeEnum {

    EMPTY_AI_RESPONSE("EMPTY_AI_RESPONSE", "模型返回空响应"),
    SECURITY_PAUSED("SECURITY_PAUSED", "安全策略暂停"),
    SECURITY_BLOCKED("SECURITY_BLOCKED", "安全策略阻断"),
    QUALITY_FEEDBACK("QUALITY_FEEDBACK", "质量检测反馈"),
    QUALITY_SHADOW_SUBMITTED("QUALITY_SHADOW_SUBMITTED", "影子质量审核已提交"),
    QUALITY_GATE("QUALITY_GATE", "质量闸门决策"),
    EXECUTION_START("EXECUTION_START", "执行开始"),
    EXECUTION_STOPPED("EXECUTION_STOPPED", "执行已停止"),
    EXECUTION_COMPLETED("EXECUTION_COMPLETED", "执行完成"),
    EXECUTION_FAILED("EXECUTION_FAILED", "执行失败"),
    ROUND_START("ROUND_START", "轮次开始"),
    AI_RESPONSE("AI_RESPONSE", "模型响应"),
    TOOL_RESULT("TOOL_RESULT", "工具结果"),
    TOOL_CALL_LIMITED("TOOL_CALL_LIMITED", "工具调用超限"),
    UNEXPECTED_TOOL_REQUEST("UNEXPECTED_TOOL_REQUEST", "非预期工具调用"),
    OUTPUT_TRUNCATED("OUTPUT_TRUNCATED", "输出被截断"),
    OUTPUT_SANITIZED("OUTPUT_SANITIZED", "输出已清洗"),
    FORCE_COMPLETION("FORCE_COMPLETION", "强制收敛"),
    DEGRADED_COMPLETION("DEGRADED_COMPLETION", "降级收敛"),
    MODEL_RESOLVED("MODEL_RESOLVED", "模型解析"),
    MODEL_RETRY("MODEL_RETRY", "模型重试"),
    MODEL_FALLBACK_SWITCH("MODEL_FALLBACK_SWITCH", "模型降级切换"),
    MODEL_FAILURE_FALLBACK("MODEL_FAILURE_FALLBACK", "模型失败降级");

    private final String code;
    private final String description;
}
