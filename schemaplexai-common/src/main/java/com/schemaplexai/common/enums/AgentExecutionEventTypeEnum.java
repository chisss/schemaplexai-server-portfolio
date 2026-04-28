package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 执行事件类型
 */
@Getter
@AllArgsConstructor
public enum AgentExecutionEventTypeEnum {

    CONNECTED("CONNECTED", "事件流已连接"),
    QUEUED("QUEUED", "执行已入队"),
    CONTEXT_INJECT("CONTEXT_INJECT", "上下文注入"),
    ROUND_START("ROUND_START", "轮次开始"),
    AI_RESPONSE("AI_RESPONSE", "模型响应"),
    TOOL_CALL("TOOL_CALL", "工具调用"),
    TOOL_RESULT("TOOL_RESULT", "工具结果"),
    QUALITY_SHADOW_SUBMITTED("QUALITY_SHADOW_SUBMITTED", "影子质量审核已提交"),
    QUALITY_SHADOW_APPLIED("QUALITY_SHADOW_APPLIED", "影子质量审核已生效"),
    QUALITY_GATE("QUALITY_GATE", "质量闸门决策"),
    REQUIRE_INPUT("REQUIRE_INPUT", "等待人工输入"),
    USER_INPUT("USER_INPUT", "收到人工输入"),
    RESUMED("RESUMED", "恢复执行"),
    SECURITY_WARN("SECURITY_WARN", "安全告警"),
    MODEL_RESOLVED("MODEL_RESOLVED", "模型解析"),
    COMPLETED("COMPLETED", "执行完成"),
    FAILED("FAILED", "执行失败"),
    CANCELLED("CANCELLED", "执行取消"),
    BLOCKED("BLOCKED", "执行阻断"),
    PAUSED("PAUSED", "执行暂停"),
    STREAM_START("STREAM_START", "流式输出开始"),
    STREAM_CHUNK("STREAM_CHUNK", "流式输出片段"),
    STREAM_END("STREAM_END", "流式输出结束"),
    THINKING("THINKING", "模型思考过程"),
    TOOL_START("TOOL_START", "工具开始执行"),
    TOOL_END("TOOL_END", "工具执行完成"),
    TOOL_PARALLEL_START("TOOL_PARALLEL_START", "并行工具组开始"),
    TOOL_PARALLEL_END("TOOL_PARALLEL_END", "并行工具组完成"),
    APPROVAL_REQUIRED("APPROVAL_REQUIRED", "需要人工审批"),
    APPROVAL_GRANTED("APPROVAL_GRANTED", "审批已通过"),
    APPROVAL_DENIED("APPROVAL_DENIED", "审批已拒绝"),
    TOOL_APPROVAL_PENDING("TOOL_APPROVAL_PENDING", "工具审批待处理"),
    TOOL_APPROVAL_RESUMED("TOOL_APPROVAL_RESUMED", "工具审批后恢复执行"),
    TOOL_POLICY_DENIED("TOOL_POLICY_DENIED", "工具被策略拒绝"),
    TOOL_SUGGEST_PREVIEW("TOOL_SUGGEST_PREVIEW", "工具建议预览"),
    PLAN_STEP("PLAN_STEP", "计划步骤"),
    SUGGESTION("SUGGESTION", "建议（仅展示不执行）");

    private final String code;
    private final String description;

    public static AgentExecutionEventTypeEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (AgentExecutionEventTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
