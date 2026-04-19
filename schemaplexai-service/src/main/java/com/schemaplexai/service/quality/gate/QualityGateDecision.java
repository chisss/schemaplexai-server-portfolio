package com.schemaplexai.service.quality.gate;

import com.schemaplexai.common.enums.GateDecisionEnum;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质量闸门决策结果
 * 支持五种决策: pass / warn / pause / fail / retry
 */
public record QualityGateDecision(
        /** 决策类型 */
        String decision,
        /** 决策说明 */
        String message,
        /** 是否暂停工作流 */
        boolean pauseWorkflow,
        /** 是否终止工作流 */
        boolean failWorkflow,
        /** retry 模式下的修正提示词（用于 reprompt） */
        String retryPrompt
) {

    public static QualityGateDecision pass(String message) {
        return new QualityGateDecision(GateDecisionEnum.PASS.getCode(), message, false, false, null);
    }

    public static QualityGateDecision warn(String message) {
        return new QualityGateDecision(GateDecisionEnum.WARN.getCode(), message, false, false, null);
    }

    public static QualityGateDecision pause(String message) {
        return new QualityGateDecision(GateDecisionEnum.PAUSE.getCode(), message, true, false, null);
    }

    public static QualityGateDecision fail(String message) {
        return new QualityGateDecision(GateDecisionEnum.FAIL.getCode(), message, false, true, null);
    }

    public static QualityGateDecision retry(String message, String retryPrompt) {
        return new QualityGateDecision(GateDecisionEnum.RETRY.getCode(), message, false, false, retryPrompt);
    }

    /** 是否需要重试 */
    public boolean shouldRetry() {
        return GateDecisionEnum.RETRY.getCode().equalsIgnoreCase(decision);
    }

    /** 是否通过（pass 或 warn） */
    public boolean isPassed() {
        return GateDecisionEnum.PASS.getCode().equalsIgnoreCase(decision)
                || GateDecisionEnum.WARN.getCode().equalsIgnoreCase(decision);
    }

    /** 转换为输出数据（兼容原有 WorkflowNodeQualityGateDecision.toOutputData） */
    public Map<String, Object> toOutputData() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("qualityGateDecision", decision);
        output.put("qualityGateMessage", message);
        output.put("qualityGateTriggered", !GateDecisionEnum.PASS.getCode().equalsIgnoreCase(decision));
        output.put("qualityGatePauseWorkflow", pauseWorkflow);
        output.put("qualityGateFailWorkflow", failWorkflow);
        if (retryPrompt != null) {
            output.put("qualityGateRetryPrompt", retryPrompt);
        }
        return output;
    }
}
