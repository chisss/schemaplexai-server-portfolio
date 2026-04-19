package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量问题类型枚举
 */
@Getter
@AllArgsConstructor
public enum QualityIssueTypeEnum {

    DEVIATION("deviation", "偏离检测"),
    INTENT_DEFECT("intent_defect", "意图缺陷");

    private final String code;
    private final String description;
}
