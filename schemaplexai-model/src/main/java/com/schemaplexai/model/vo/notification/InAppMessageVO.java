package com.schemaplexai.model.vo.notification;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 站内信收件箱项
 */
@Data
public class InAppMessageVO {

    private String recipientId;

    private String messageId;

    private String title;

    private String content;

    private String messageType;

    private String businessType;

    private String sourceType;

    private String sourceId;

    private String actionUrl;

    private String status;

    private LocalDateTime readAt;

    private LocalDateTime archivedAt;

    private Map<String, Object> payload;

    private LocalDateTime createdAt;
}
