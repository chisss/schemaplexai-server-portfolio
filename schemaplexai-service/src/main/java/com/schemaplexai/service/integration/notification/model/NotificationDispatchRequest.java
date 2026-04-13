package com.schemaplexai.service.integration.notification.model;

import com.schemaplexai.model.entity.NotificationChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知分发请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDispatchRequest {

    private NotificationChannel channel;

    private NotificationMessage message;

    private String templateId;

    private String templateName;

    private String templateType;

    private String sourceType;

    private String sourceId;

    private String businessType;

    private String workflowInstanceId;

    private String workflowNodeExecutionId;

    private String specId;
}
