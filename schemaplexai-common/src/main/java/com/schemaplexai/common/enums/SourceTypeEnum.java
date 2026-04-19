package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具来源类型枚举
 */
@Getter
@AllArgsConstructor
public enum SourceTypeEnum {

    BUILTIN("builtin", "内置系统工具"),
    MCP("mcp", "MCP 协议工具"),
    SKILL("skill", "Skill 工具"),
    API_GATEWAY("api_gateway", "API 网关工具");

    private final String code;
    private final String description;

    public static SourceTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SourceTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
