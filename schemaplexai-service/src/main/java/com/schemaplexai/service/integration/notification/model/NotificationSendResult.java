package com.schemaplexai.service.integration.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 通知发送结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationSendResult {

    private String recordId;

    private String channelType;

    private String status;

    private Map<String, Object> requestPayload;

    private Map<String, Object> responsePayload;

    private String responseSummary;

    private String providerMessageId;

    private String errorMessage;

    private BigDecimal billingAmount;

    private String billingCurrency;

    private String billingUnit;

    private Integer billingQuantity;
}
