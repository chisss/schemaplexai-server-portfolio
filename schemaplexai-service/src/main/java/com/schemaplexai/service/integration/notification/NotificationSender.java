package com.schemaplexai.service.integration.notification;

import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import org.springframework.util.StringUtils;

import java.util.Map;

public interface NotificationSender {

    /**
     * 渠道类型
     */
    String getChannelType();

    /**
     * 发送消息
     */
    NotificationSendResult sendMessage(Map<String, Object> config, NotificationMessage message) throws Exception;

    /**
     * 发送测试消息
     */
    default NotificationSendResult sendTestMessage(Map<String, Object> config) throws Exception {
        return sendMessage(config, NotificationMessage.builder()
                .title("[SchemaPlexAI] 渠道测试")
                .content("SchemaPlexAI 通知渠道配置验证成功，此消息来自渠道测试功能。")
                .build());
    }

    /**
     * 组装纯文本消息内容，并避免重复追加预览链接
     */
    default String composePlainText(NotificationMessage message, String previewLabel) {
        StringBuilder builder = new StringBuilder();
        if (message != null && StringUtils.hasText(message.getTitle())) {
            builder.append(message.getTitle().trim());
        }
        if (message != null && StringUtils.hasText(message.getContent())) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(message.getContent().trim());
        }
        if (shouldAppendPreviewUrl(message)) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(previewLabel).append(message.getPreviewUrl().trim());
        }
        return builder.toString();
    }

    /**
     * 当前正文未包含预览链接时才额外追加
     */
    default boolean shouldAppendPreviewUrl(NotificationMessage message) {
        if (message == null || !StringUtils.hasText(message.getPreviewUrl())) {
            return false;
        }
        if (!StringUtils.hasText(message.getContent())) {
            return true;
        }
        return !message.getContent().contains(message.getPreviewUrl().trim());
    }
}
