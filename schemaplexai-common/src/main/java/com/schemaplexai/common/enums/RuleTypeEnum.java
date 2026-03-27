package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量规则类型枚举
 */
@Getter
@AllArgsConstructor
public enum RuleTypeEnum {

    STRUCTURAL("structural", "结构性偏离"),
    SEMANTIC("semantic", "语义性偏离"),
    PERFORMANCE("performance", "性能偏离"),
    SECURITY("security", "安全偏离"),
    AMBIGUITY("ambiguity", "需求歧义"),
    CONTRADICTION("contradiction", "需求矛盾"),
    OMISSION("omission", "需求遗漏"),
    VAGUENESS("vagueness", "需求模糊"),
    CUSTOM("custom", "自定义规则");

    private final String code;
    private final String description;
}
