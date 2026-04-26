package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent执行模式枚举
 */
@Getter
@AllArgsConstructor
public enum ExecutionModeEnum {

    AUTO("auto", "全自动模式"),
    PLAN("plan", "计划模式"),
    SUGGEST("suggest", "建议模式");

    private final String code;
    private final String description;

    public static ExecutionModeEnum fromCode(String code) {
        if (code == null) {
            return AUTO;
        }
        for (ExecutionModeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return AUTO;
    }
}
