package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Skill状态枚举
 */
@Getter
@AllArgsConstructor
public enum SkillStatusEnum {

    ACTIVE("active", "已启用"),
    INACTIVE("inactive", "已停用"),
    DEPRECATED("deprecated", "已废弃");

    private final String code;
    private final String description;
}
