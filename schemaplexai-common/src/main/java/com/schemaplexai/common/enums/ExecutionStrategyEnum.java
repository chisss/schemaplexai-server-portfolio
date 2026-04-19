package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量检查执行策略枚举
 */
@Getter
@AllArgsConstructor
public enum ExecutionStrategyEnum {

    SYNC("sync", "同步规则检查"),
    SHORT_WAIT("short_wait", "同步等待模型评估"),
    ASYNC("async", "异步深度审查");

    private final String code;
    private final String description;
}
