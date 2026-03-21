package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作流实例状态枚举
 */
@Getter
@AllArgsConstructor
public enum WorkflowInstanceStatusEnum {

    PENDING("pending", "待启动"),
    RUNNING("running", "运行中"),
    PAUSED("paused", "已暂停"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "已失败"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String description;
}
