package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Skill 实现类型枚举
 */
@Getter
@AllArgsConstructor
public enum SkillImplementationTypeEnum {

    MCP("mcp", "MCP 协议实现"),
    BUILTIN("builtin", "内置 Java 类实现"),
    SCRIPT("script", "脚本实现"),
    API("api", "API 实现");

    private final String code;
    private final String description;

    public static SkillImplementationTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SkillImplementationTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
