package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量任务状态枚举
 */
@Getter
@AllArgsConstructor
public enum TaskStatusEnum {

    PENDING("pending", "待执行"),
    RUNNING("running", "执行中"),
    SUCCEEDED("succeeded", "执行成功"),
    FAILED("failed", "执行失败"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String description;
}
