package com.schemaplexai.model.vo.channel;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知渠道视图对象
 */
@Data
public class NotificationChannelVO {

    /** 主键ID */
    private String id;

    /** 渠道名称 */
    private String name;

    /** 渠道类型 */
    private String channelType;

    /** 脱敏后的配置信息 */
    private Map<String, Object> config;

    /** 状态 */
    private String status;

    /** 通知规则 */
    private Map<String, Object> notificationRules;

    /** 最近测试时间 */
    private LocalDateTime lastTestAt;

    /** 错误信息 */
    private String errorMessage;

    /** 描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 创建人名称 */
    private String createdByName;
}
