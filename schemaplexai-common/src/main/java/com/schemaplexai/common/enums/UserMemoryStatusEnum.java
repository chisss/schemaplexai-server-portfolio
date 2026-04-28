package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户记忆状态枚举
 */
@Getter
@AllArgsConstructor
public enum UserMemoryStatusEnum {

    CANDIDATE("CANDIDATE", "候选"),
    ACTIVE("ACTIVE", "生效"),
    REJECTED("REJECTED", "已拒绝"),
    ARCHIVED("ARCHIVED", "已归档");

    private final String code;
    private final String description;
}
