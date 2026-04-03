package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP Server 分类枚举
 */
@Getter
@AllArgsConstructor
public enum McpServerTypeEnum {

    GENERIC("generic", "通用 MCP Server"),
    DATABASE("database", "数据库 MCP Server");

    private final String code;
    private final String description;

    public static McpServerTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (McpServerTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
