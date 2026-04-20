package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审批修正匹配类型枚举
 */
@Getter
@AllArgsConstructor
public enum AmendmentMatchTypeEnum {

    PREFIX("PREFIX", "前缀匹配"),
    EXACT("EXACT", "精确匹配"),
    REGEX("REGEX", "正则匹配");

    private final String code;
    private final String description;
}
