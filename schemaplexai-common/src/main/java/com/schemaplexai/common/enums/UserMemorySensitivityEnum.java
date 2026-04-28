package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户记忆敏感级别枚举
 */
@Getter
@AllArgsConstructor
public enum UserMemorySensitivityEnum {

    NORMAL("NORMAL", "普通"),
    SENSITIVE("SENSITIVE", "敏感"),
    RESTRICTED("RESTRICTED", "受限");

    private final String code;
    private final String description;
}
