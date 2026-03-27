package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具执行状态枚举
 */
@Getter
@AllArgsConstructor
public enum ToolExecutionStatusEnum {

    SUCCESS("success", "执行成功"),
    FAILED("failed", "执行失败"),
    ERROR("error", "执行异常");

    private final String code;
    private final String description;
}
