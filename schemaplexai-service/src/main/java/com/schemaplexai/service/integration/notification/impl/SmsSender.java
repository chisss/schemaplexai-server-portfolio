package com.schemaplexai.service.integration.notification.impl;

import com.schemaplexai.service.integration.notification.NotificationSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 短信消息发送器
 * 预留接口，具体实现需对接第三方短信平台（如阿里云短信、腾讯云短信）
 */
@Slf4j
@Component
public class SmsSender implements NotificationSender {

    @Override
    public String getChannelType() {
        return "sms";
    }

    @Override
    public String sendTestMessage(Map<String, Object> config) throws Exception {
        String provider = String.valueOf(config.getOrDefault("provider", ""));
        String phone = String.valueOf(config.getOrDefault("phone", ""));
        if (phone.isBlank()) {
            throw new IllegalArgumentException("短信配置缺少手机号");
        }

        // 预留: 根据 provider 对接不同短信平台
        // 目前记录日志并返回模拟成功
        log.info("短信测试消息发送（模拟）: provider={}, phone={}", provider, phone);
        return "短信发送成功（模拟模式，需配置短信平台 SDK）";
    }
}
