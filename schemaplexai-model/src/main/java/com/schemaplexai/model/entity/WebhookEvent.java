package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Webhook事件日志表实体
 */
@Data
@TableName(value = "sf_webhook_event", autoResultMap = true)
public class WebhookEvent implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String integrationId;
    /** 事件类型: push/pull_request/tag/build_status */
    private String eventType;
    private String sourcePlatform;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;
    /** 状态: received/processing/processed/failed */
    private String status;
    private LocalDateTime processedAt;
    private Integer retryCount;
    private String errorMessage;
    private LocalDateTime createdAt;
}
