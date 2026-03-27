package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 评审会话状态枚举
 */
@Getter
@AllArgsConstructor
public enum ReviewSessionStatusEnum {

    PENDING("pending", "待开始"),
    IN_PROGRESS("in_progress", "进行中"),
    COMPLETED("completed", "已完成"),
    TIMEOUT("timeout", "已超时");

    private final String code;
    private final String description;
}
