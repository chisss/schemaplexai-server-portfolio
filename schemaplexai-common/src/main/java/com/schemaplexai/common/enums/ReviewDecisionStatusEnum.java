package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 评审决策状态
 */
@Getter
@AllArgsConstructor
public enum ReviewDecisionStatusEnum {

    PENDING("pending", "待决策"),
    APPROVED("approved", "已通过"),
    REJECTED("rejected", "已驳回"),
    REQUEST_MODIFY("request_modify", "退回修改");

    private final String code;

    private final String description;
}
