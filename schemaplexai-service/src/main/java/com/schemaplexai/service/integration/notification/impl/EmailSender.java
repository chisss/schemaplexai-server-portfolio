package com.schemaplexai.service.integration.notification.impl;

import com.schemaplexai.service.integration.notification.AbstractNotificationSender;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 邮件消息发送器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender extends AbstractNotificationSender {

    private final JavaMailSender javaMailSender;

    @Override
    public String getChannelType() {
        return "email";
    }

    @Override
    protected NotificationSendResult doSend(Map<String, Object> config, NotificationMessage notificationMessage) throws Exception {
        String fromAddr = String.valueOf(config.getOrDefault("from", ""));
        String toAddr = String.valueOf(config.getOrDefault("to", ""));
        if (fromAddr.isBlank() || toAddr.isBlank()) {
            throw new IllegalArgumentException("邮件配置缺少 from 或 to 地址");
        }

        MimeMessage mailMessage = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mailMessage, true, "UTF-8");
        helper.setFrom(fromAddr);
        helper.setTo(toAddr.split(","));
        helper.setSubject(resolveSubject(notificationMessage));
        helper.setText(resolveHtml(notificationMessage), true);

        javaMailSender.send(mailMessage);
        log.info("邮件消息发送成功: from={}, to={}", fromAddr, toAddr);
        NotificationSendResult result = buildSuccessResult(
                Map.of(
                        "from", fromAddr,
                        "to", toAddr,
                        "subject", resolveSubject(notificationMessage),
                        "html", resolveHtml(notificationMessage)
                ),
                Map.of("raw", "邮件发送成功"),
                "邮件发送成功"
        );
        applyBilling(result, BigDecimal.ZERO, "CNY", "email", 1);
        return result;
    }

    private String resolveSubject(NotificationMessage message) {
        if (message == null || message.getTitle() == null || message.getTitle().isBlank()) {
            return "[SchemaPlexAI] 通知消息";
        }
        return message.getTitle();
    }

    private String resolveHtml(NotificationMessage message) {
        if (message == null) {
            return "<p>无消息内容</p>";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("<h3>").append(resolveSubject(message)).append("</h3>");
        if (StringUtils.hasText(message.getContent())) {
            builder.append("<p>").append(message.getContent().trim().replace("\n", "<br/>")).append("</p>");
        }
        if (shouldAppendPreviewUrl(message)) {
            builder.append("<p>预览链接：<a href=\"")
                    .append(message.getPreviewUrl().trim())
                    .append("\">")
                    .append(message.getPreviewUrl().trim())
                    .append("</a></p>");
        }
        return builder.toString();
    }
}
