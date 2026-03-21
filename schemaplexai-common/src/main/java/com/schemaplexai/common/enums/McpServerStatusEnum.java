package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * MCP Server状态枚举
 */
@Getter
@AllArgsConstructor
public enum McpServerStatusEnum {

    ACTIVE("active", "已连接"),
    INACTIVE("inactive", "未连接"),
    ERROR("error", "连接异常");

    private final String code;
    private final String description;
}
