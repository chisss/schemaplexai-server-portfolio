package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户记忆作用域枚举
 */
@Getter
@AllArgsConstructor
public enum UserMemoryScopeEnum {

    USER_GLOBAL("USER_GLOBAL", "用户全局"),
    USER_AGENT("USER_AGENT", "用户-智能体"),
    USER_PROJECT("USER_PROJECT", "用户-项目"),
    USER_WORKSPACE("USER_WORKSPACE", "用户-工作区");

    private final String code;
    private final String description;
}
