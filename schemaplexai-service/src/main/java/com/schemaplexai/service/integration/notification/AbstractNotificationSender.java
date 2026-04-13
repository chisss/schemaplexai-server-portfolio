package com.schemaplexai.service.integration.notification;

import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 通知发送器抽象基类
 */
public abstract class AbstractNotificationSender implements NotificationSender {

    private static final String STATUS_SUCCESS = "success";

    @Override
    public final NotificationSendResult sendMessage(Map<String, Object> config, NotificationMessage message) throws Exception {
        NotificationSendResult result = doSend(config, message);
        if (result == null) {
            result = NotificationSendResult.builder().build();
        }
        if (!StringUtils.hasText(result.getChannelType())) {
            result.setChannelType(getChannelType());
        }
        if (!StringUtils.hasText(result.getStatus())) {
            result.setStatus(STATUS_SUCCESS);
        }
        if (result.getBillingQuantity() == null) {
            result.setBillingQuantity(1);
        }
        return result;
    }

    protected abstract NotificationSendResult doSend(Map<String, Object> config, NotificationMessage message) throws Exception;

    protected NotificationSendResult buildSuccessResult(Map<String, Object> requestPayload,
                                                        Map<String, Object> responsePayload,
                                                        String responseSummary) {
        return NotificationSendResult.builder()
                .channelType(getChannelType())
                .status(STATUS_SUCCESS)
                .requestPayload(requestPayload)
                .responsePayload(responsePayload)
                .responseSummary(responseSummary)
                .build();
    }

    protected void applyBilling(NotificationSendResult result,
                                BigDecimal billingAmount,
                                String billingCurrency,
                                String billingUnit,
                                Integer billingQuantity) {
        result.setBillingAmount(billingAmount);
        result.setBillingCurrency(billingCurrency);
        result.setBillingUnit(billingUnit);
        result.setBillingQuantity(billingQuantity);
    }
}
