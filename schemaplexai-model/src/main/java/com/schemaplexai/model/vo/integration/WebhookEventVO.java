package com.schemaplexai.model.vo.integration;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook事件视图对象
 */
@Data
public class WebhookEventVO {

    /** 主键ID */
    private String id;

    /** 集成配置ID */
    private String integrationId;

    /** 事件类型 */
    private String eventType;

    /** 来源平台 */
    private String sourcePlatform;

    /** 事件负载 */
    private Map<String, Object> payload;

    /** 处理状态 */
    private String status;

    /** 处理时间 */
    private LocalDateTime processedAt;

    /** 重试次数 */
    private Integer retryCount;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
