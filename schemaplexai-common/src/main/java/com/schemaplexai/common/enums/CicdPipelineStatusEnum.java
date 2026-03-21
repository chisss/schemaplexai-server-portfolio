package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * CICD Pipeline状态枚举
 */
@Getter
@AllArgsConstructor
public enum CicdPipelineStatusEnum {

    INACTIVE("inactive", "未激活"),
    ACTIVE("active", "已激活"),
    PENDING("pending", "等待执行"),
    RUNNING("running", "执行中"),
    SUCCESS("success", "执行成功"),
    FAILED("failed", "执行失败"),
    CANCELLED("cancelled", "已取消");

    private final String code;
    private final String description;
}
