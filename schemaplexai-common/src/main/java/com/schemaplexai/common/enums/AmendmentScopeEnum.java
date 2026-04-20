package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审批修正作用域枚举
 */
@Getter
@AllArgsConstructor
public enum AmendmentScopeEnum {

    AGENT("AGENT", "仅当前Agent"),
    TENANT("TENANT", "租户全局");

    private final String code;
    private final String description;
}
