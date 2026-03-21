package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 评审意见级别枚举
 */
@Getter
@AllArgsConstructor
public enum ReviewCommentLevelEnum {

    CRITICAL("Critical", "严重问题"),
    WARNING("Warning", "警告"),
    INFO("Info", "建议");

    private final String code;
    private final String description;
}
