package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 偏离状态枚举
 */
@Getter
@AllArgsConstructor
public enum DeviationStatusEnum {

    OPEN("open", "待处理"),
    ACKNOWLEDGED("acknowledged", "已确认"),
    RESOLVED("resolved", "已解决"),
    IGNORED("ignored", "已忽略");

    private final String code;
    private final String description;
}
