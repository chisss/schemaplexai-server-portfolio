package com.schemaplexai.model.vo.notification;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知记录视图对象
 */
@Data
public class NotificationRecordVO {

    private String id;

    private String channelId;

    private String channelName;

    private String channelType;

    private String templateId;

    private String templateName;

    private String templateType;

    private String sourceType;

    private String sourceId;

    private String businessType;

    private String workflowInstanceId;

    private String workflowNodeExecutionId;

    private String specId;

    private String status;

    private String title;

    private String content;

    private Map<String, Object> requestPayload;

    private Map<String, Object> responsePayload;

    private String responseSummary;

    private String providerMessageId;

    private String errorMessage;

    private BigDecimal billingAmount;

    private String billingCurrency;

    private String billingUnit;

    private Integer billingQuantity;

    private LocalDateTime sentAt;

    private LocalDateTime createdAt;
}
