package com.schemaplexai.model.dto.channel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建通知渠道请求
 */
@Data
public class NotificationChannelCreateRequest {

    /** 渠道名称 */
    @NotBlank(message = "渠道名称不能为空")
    @Size(max = 100, message = "渠道名称不能超过100个字符")
    private String name;

    /** 渠道类型: dingtalk/wechat_work/feishu/slack/email/sms */
    @NotBlank(message = "渠道类型不能为空")
    private String channelType;

    /** 配置信息 */
    @NotNull(message = "配置信息不能为空")
    private Map<String, Object> config;

    /** 描述 */
    private String description;

    /** 通知规则 */
    private Map<String, Object> notificationRules;
}
