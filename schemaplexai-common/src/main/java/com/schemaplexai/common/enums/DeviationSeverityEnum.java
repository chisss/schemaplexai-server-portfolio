package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 偏离严重程度枚举
 */
@Getter
@AllArgsConstructor
public enum DeviationSeverityEnum {

    CRITICAL("critical", "阻断"),
    WARNING("warning", "告警"),
    INFO("info", "记录");

    private final String code;
    private final String description;
}
