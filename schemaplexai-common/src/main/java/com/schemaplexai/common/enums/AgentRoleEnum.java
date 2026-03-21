package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent角色枚举
 */
@Getter
@AllArgsConstructor
public enum AgentRoleEnum {

    LEAD("lead", "Lead Agent"),
    FRONTEND("frontend", "前端 Sub-Agent"),
    BACKEND("backend", "后端 Sub-Agent"),
    TEST("test", "测试 Sub-Agent"),
    DOC("doc", "文档 Sub-Agent"),
    REVIEW("review", "评审 Sub-Agent");

    private final String code;
    private final String description;
}
