package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent类型枚举
 */
@Getter
@AllArgsConstructor
public enum AgentTypeEnum {

    SOLO("solo", "Solo Agent"),
    TEAM("team", "Team Agent");

    private final String code;
    private final String description;

    public static AgentTypeEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return SOLO;
        }
        for (AgentTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return SOLO;
    }
}
