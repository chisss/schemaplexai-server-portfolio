package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 上下文绑定来源
 */
@Getter
@AllArgsConstructor
public enum AgentContextBindingSourceEnum {

    MANUAL("manual", "手动输入"),
    CONTEXT_MODULE("context_module", "上下文模块"),
    GITLAB("gitlab", "GitLab");

    private final String code;
    private final String description;

    public static AgentContextBindingSourceEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        for (AgentContextBindingSourceEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return null;
    }
}
