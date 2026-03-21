package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 意图缺陷类型枚举
 */
@Getter
@AllArgsConstructor
public enum IntentDefectTypeEnum {

    AMBIGUITY("ambiguity", "需求歧义"),
    CONTRADICTION("contradiction", "需求矛盾"),
    OMISSION("omission", "需求遗漏"),
    VAGUENESS("vagueness", "需求模糊");

    private final String code;
    private final String description;
}
