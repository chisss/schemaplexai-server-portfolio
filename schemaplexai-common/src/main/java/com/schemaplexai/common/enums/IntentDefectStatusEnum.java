package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 意图缺陷状态枚举
 */
@Getter
@AllArgsConstructor
public enum IntentDefectStatusEnum {

    OPEN("open", "待处理"),
    RESOLVED("resolved", "已解决"),
    ACCEPTED("accepted", "已接受");

    private final String code;
    private final String description;
}
