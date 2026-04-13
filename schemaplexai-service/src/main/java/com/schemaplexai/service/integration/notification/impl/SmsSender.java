package com.schemaplexai.service.integration.notification.impl;

import com.schemaplexai.service.integration.notification.AbstractNotificationSender;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 短信消息发送器
 * 预留接口，具体实现需对接第三方短信平台（如阿里云短信、腾讯云短信）
 */
@Slf4j
@Component
public class SmsSender extends AbstractNotificationSender {

    @Override
    public String getChannelType() {
        return "sms";
    }

    @Override
    protected NotificationSendResult doSend(Map<String, Object> config, NotificationMessage message) {
        String provider = String.valueOf(config.getOrDefault("provider", ""));
        String phone = String.valueOf(config.getOrDefault("phone", ""));
        if (phone.isBlank()) {
            throw new IllegalArgumentException("短信配置缺少手机号");
        }

        log.info("短信消息发送（模拟）: provider={}, phone={}, title={}, content={}",
                provider, phone, message != null ? message.getTitle() : null, message != null ? message.getContent() : null);
        Map<String, Object> requestPayload = Map.of(
                "provider", provider,
                "phone", phone,
                "title", message != null ? message.getTitle() : "",
                "content", message != null ? message.getContent() : ""
        );
        NotificationSendResult result = buildSuccessResult(
                requestPayload,
                Map.of("raw", "短信发送成功（模拟模式，需配置短信平台 SDK）"),
                "短信发送成功（模拟模式，需配置短信平台 SDK）"
        );
        applyBilling(
                result,
                resolveBillingAmount(config),
                String.valueOf(config.getOrDefault("billing_currency", "CNY")),
                "sms",
                1
        );
        return result;
    }

    private BigDecimal resolveBillingAmount(Map<String, Object> config) {
        Object unitPrice = config.getOrDefault("unit_price", config.get("unitPrice"));
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(unitPrice));
    }
}
