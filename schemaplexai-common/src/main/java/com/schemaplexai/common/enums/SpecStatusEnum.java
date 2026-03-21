package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Spec状态枚举
 */
@Getter
@AllArgsConstructor
public enum SpecStatusEnum {

    DRAFT("draft", "草稿"),
    REQUIREMENTS_REVIEW("requirements_review", "需求评审中"),
    REQUIREMENTS_APPROVED("requirements_approved", "需求已通过"),
    DESIGN_REVIEW("design_review", "设计评审中"),
    DESIGN_APPROVED("design_approved", "设计已通过"),
    TASKS_REVIEW("tasks_review", "任务评审中"),
    READY("ready", "就绪"),
    IN_PROGRESS("in_progress", "进行中"),
    COMPLETED("completed", "已完成"),
    ACCEPTANCE("acceptance", "验收中"),
    ARCHIVED("archived", "已归档");

    private final String code;
    private final String description;
}
