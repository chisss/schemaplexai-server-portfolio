package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作空间状态枚举
 */
@Getter
@AllArgsConstructor
public enum WorkspaceStatusEnum {

    CLONING("cloning", "克隆中"),
    READY("ready", "就绪"),
    SYNCING("syncing", "同步中"),
    ERROR("error", "异常"),
    ARCHIVED("archived", "已归档");

    private final String code;
    private final String description;
}
