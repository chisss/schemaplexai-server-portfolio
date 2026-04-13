package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Spec 生命周期模式
 */
@Getter
@AllArgsConstructor
public enum SpecLifecycleModeEnum {

    LEGACY("legacy", "固定三阶段生命周期"),
    WORKFLOW("workflow", "工作流驱动生命周期");

    private final String code;

    private final String description;
}
