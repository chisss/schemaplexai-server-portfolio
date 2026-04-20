package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Team Agent 成员执行模式
 */
@Getter
@AllArgsConstructor
public enum TeamMemberExecutionMode {

    PIPELINE("pipeline", "管道模式：串行执行，上游输出作为下游证据"),
    PARALLEL("parallel", "并行模式：无依赖成员并行执行"),
    ;

    private final String code;
    private final String description;

    public static TeamMemberExecutionMode fromCode(String code) {
        if (code == null) {
            return PIPELINE;
        }
        for (TeamMemberExecutionMode mode : values()) {
            if (mode.code.equalsIgnoreCase(code.trim())) {
                return mode;
            }
        }
        return PIPELINE;
    }
}
