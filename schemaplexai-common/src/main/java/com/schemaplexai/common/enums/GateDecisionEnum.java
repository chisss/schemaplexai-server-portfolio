package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量闸门决策枚举
 */
@Getter
@AllArgsConstructor
public enum GateDecisionEnum {

    PASS("pass", "通过"),
    WARN("warn", "告警"),
    PAUSE("pause", "暂停"),
    FAIL("fail", "终止"),
    RETRY("retry", "重试");

    private final String code;
    private final String description;
}
