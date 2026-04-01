package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent执行状态枚举
 */
@Getter
@AllArgsConstructor
public enum AgentExecutionStatusEnum {

    QUEUED("queued", "排队中"),
    PAUSED("paused", "已暂停"),
    RUNNING("running", "执行中"),
    COMPLETED("completed", "执行完成"),
    FAILED("failed", "执行失败"),
    CANCELLED("cancelled", "已取消"),
    STOPPED("stopped", "已停止");

    private final String code;
    private final String description;
}
