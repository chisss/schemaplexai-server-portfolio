package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent记忆阶段枚举
 */
@Getter
@AllArgsConstructor
public enum MemoryPhaseEnum {

    RAW("RAW", "原始提取"),
    CONSOLIDATED("CONSOLIDATED", "已合并");

    private final String code;
    private final String description;
}
