package com.schemaplexai.model.dto.channel;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 更新通知渠道请求
 */
@Data
public class NotificationChannelUpdateRequest {

    /** 渠道名称 */
    @Size(max = 100, message = "渠道名称不能超过100个字符")
    private String name;

    /** 配置信息 */
    private Map<String, Object> config;

    /** 状态: active/inactive */
    private String status;

    /** 描述 */
    private String description;

    /** 通知规则 */
    private Map<String, Object> notificationRules;
}
