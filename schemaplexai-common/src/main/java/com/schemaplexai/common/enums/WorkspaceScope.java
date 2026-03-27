package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作空间作用域
 */
@Getter
@AllArgsConstructor
public enum WorkspaceScope {

    PROJECT("PROJECT", "项目型工作空间"),
    SYSTEM("SYSTEM", "系统型工作空间");

    private final String code;
    private final String description;
}
