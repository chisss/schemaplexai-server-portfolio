package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 偏离类型枚举
 */
@Getter
@AllArgsConstructor
public enum DeviationTypeEnum {

    STRUCTURAL("structural", "结构性偏离"),
    SEMANTIC("semantic", "语义性偏离"),
    PERFORMANCE("performance", "性能偏离"),
    SECURITY("security", "安全偏离");

    private final String code;
    private final String description;
}
