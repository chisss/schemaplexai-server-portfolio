package com.schemaplexai.common.enums;

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
}
