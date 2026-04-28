package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具执行网关决策
 */
@Getter
@AllArgsConstructor
public enum ToolExecutionDecisionEnum {

    EXECUTE("EXECUTE", "直接执行"),
    PAUSE_APPROVAL("PAUSE_APPROVAL", "暂停等待审批"),
    SUGGEST_ONLY("SUGGEST_ONLY", "仅建议不执行"),
    DENY("DENY", "策略拒绝"),
    EDIT_REQUIRED("EDIT_REQUIRED", "需要修改参数");

    private final String code;
    private final String description;
}
