package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 用户记忆类型枚举
 */
@Getter
@AllArgsConstructor
public enum UserMemoryKindEnum {

    PROFILE_FACT("PROFILE_FACT", "用户事实"),
    PREFERENCE("PREFERENCE", "用户偏好"),
    COMMUNICATION_STYLE("COMMUNICATION_STYLE", "沟通风格"),
    WORKFLOW_RULE("WORKFLOW_RULE", "工作规则"),
    TOOL_PREFERENCE("TOOL_PREFERENCE", "工具偏好"),
    AVOIDANCE("AVOIDANCE", "规避事项"),
    EPISODIC_SUMMARY("EPISODIC_SUMMARY", "事件摘要");

    private final String code;
    private final String description;
}
