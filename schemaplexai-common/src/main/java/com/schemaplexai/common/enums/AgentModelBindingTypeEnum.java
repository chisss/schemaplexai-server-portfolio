package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 模型绑定类型
 */
@Getter
@AllArgsConstructor
public enum AgentModelBindingTypeEnum {

    MODEL("model", "单模型"),
    MODEL_GROUP("model_group", "模型组");

    private final String code;
    private final String description;

    public static AgentModelBindingTypeEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return MODEL;
        }
        for (AgentModelBindingTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return MODEL;
    }
}
