package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Skill类别枚举
 */
@Getter
@AllArgsConstructor
public enum SkillCategoryEnum {

    BUILTIN("builtin", "内置技能"),
    CUSTOM("custom", "自定义技能"),
    MCP("mcp", "MCP工具技能");

    private final String code;
    private final String description;
}
