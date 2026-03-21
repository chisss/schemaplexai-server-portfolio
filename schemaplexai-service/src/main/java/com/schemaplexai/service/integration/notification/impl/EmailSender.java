package com.schemaplexai.service.integration.notification.impl;

import com.schemaplexai.service.integration.notification.NotificationSender;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 邮件消息发送器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender implements NotificationSender {

    private final JavaMailSender javaMailSender;

    @Override
    public String getChannelType() {
        return "email";
    }

    @Override
    public String sendTestMessage(Map<String, Object> config) throws Exception {
        String fromAddr = String.valueOf(config.getOrDefault("from", ""));
        String toAddr = String.valueOf(config.getOrDefault("to", ""));
        if (fromAddr.isBlank() || toAddr.isBlank()) {
            throw new IllegalArgumentException("邮件配置缺少 from 或 to 地址");
        }

        MimeMessage message = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(fromAddr);
        helper.setTo(toAddr.split(","));
        helper.setSubject("[SchemaPlexAI] 通知渠道测试");
        helper.setText("<h3>SchemaPlexAI 邮件通知渠道配置验证成功</h3><p>此邮件为系统测试消息，无需回复。</p>", true);

        javaMailSender.send(message);
        log.info("邮件测试消息发送成功: from={}, to={}", fromAddr, toAddr);
        return "邮件发送成功";
    }
}
