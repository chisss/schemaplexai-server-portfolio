package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent状态枚举
 */
@Getter
@AllArgsConstructor
public enum AgentStatusEnum {

    ACTIVE("active", "已激活"),
    INACTIVE("inactive", "已停用"),
    RUNNING("running", "运行中"),
    ERROR("error", "异常");

    private final String code;
    private final String description;
}
