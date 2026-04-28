package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户记忆来源枚举
 */
@Getter
@AllArgsConstructor
public enum UserMemorySourceTypeEnum {

    EXPLICIT("EXPLICIT", "用户显式要求"),
    IMPLICIT("IMPLICIT", "系统隐式提取"),
    MANUAL("MANUAL", "用户手动维护"),
    IMPORT("IMPORT", "导入"),
    SYSTEM_MIGRATION("SYSTEM_MIGRATION", "系统迁移");

    private final String code;
    private final String description;
}
