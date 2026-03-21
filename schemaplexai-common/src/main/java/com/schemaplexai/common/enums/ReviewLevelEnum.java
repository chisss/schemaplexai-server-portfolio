package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审批级别枚举
 */
@Getter
@AllArgsConstructor
public enum ReviewLevelEnum {

    SINGLE("single", "单人审批"),
    SEQUENTIAL("sequential", "顺序会签"),
    PARALLEL("parallel", "并行会审");

    private final String code;
    private final String description;
}
