package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent记忆类型枚举
 */
@Getter
@AllArgsConstructor
public enum MemoryTypeEnum {

    FACT("FACT", "事实"),
    PREFERENCE("PREFERENCE", "偏好"),
    CONSTRAINT("CONSTRAINT", "约束"),
    PATTERN("PATTERN", "模式");

    private final String code;
    private final String description;
}
