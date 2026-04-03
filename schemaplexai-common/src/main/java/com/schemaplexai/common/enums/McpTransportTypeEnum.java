package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP 传输类型枚举
 */
@Getter
@AllArgsConstructor
public enum McpTransportTypeEnum {

    STREAMABLE_HTTP("streamable_http", "HTTP Streamable"),
    SSE("sse", "HTTP SSE"),
    STDIO("stdio", "STDIO");

    private final String code;
    private final String description;

    public static McpTransportTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (McpTransportTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
