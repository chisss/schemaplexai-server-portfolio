package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent 运行时引擎
 */
@Getter
@AllArgsConstructor
public enum AgentRuntimeEngineEnum {

    SOLO_LANGCHAIN4J("solo_langchain4j", "Solo Agent LangChain4J"),
    TEAM_LANGGRAPH4J("team_langgraph4j", "Team Agent LangGraph4J");

    private final String code;
    private final String description;

    public static AgentRuntimeEngineEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return SOLO_LANGCHAIN4J;
        }
        for (AgentRuntimeEngineEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return SOLO_LANGCHAIN4J;
    }
}
