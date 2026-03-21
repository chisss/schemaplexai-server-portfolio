package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知渠道状态枚举
 */
@Getter
@AllArgsConstructor
public enum NotificationChannelStatusEnum {

    ACTIVE("active", "已启用"),
    INACTIVE("inactive", "未启用"),
    ERROR("error", "异常");

    private final String code;
    private final String description;
}
