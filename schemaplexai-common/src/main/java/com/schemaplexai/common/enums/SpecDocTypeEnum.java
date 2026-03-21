package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Spec文档类型枚举
 */
@Getter
@AllArgsConstructor
public enum SpecDocTypeEnum {

    REQUIREMENTS("requirements", "需求文档"),
    DESIGN("design", "设计文档"),
    TASKS("tasks", "任务文档");

    private final String code;
    private final String description;
}
