package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 上下文层级枚举
 */
@Getter
@AllArgsConstructor
public enum ContextLevelEnum {

    GLOBAL("global", "全局上下文"),
    PROJECT("project", "项目上下文"),
    TASK("task", "任务上下文"),
    AGENT("agent", "Agent上下文");

    private final String code;
    private final String description;
}
