package com.schemaplexai.service.integration.notification.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.integration.notification.AbstractNotificationSender;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.math.BigDecimal;

/**
 * 钉钉 Webhook 消息发送器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DingtalkSender extends AbstractNotificationSender {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "dingtalk";
    }

    @Override
    protected NotificationSendResult doSend(Map<String, Object> config, NotificationMessage message) throws Exception {
        String webhookUrl = String.valueOf(config.getOrDefault("webhook_url", ""));
        if (webhookUrl.isBlank()) {
            throw new IllegalArgumentException("钉钉 webhook_url 未配置");
        }

        String content = buildContent(message);
        Map<String, Object> body = Map.of(
                "msgtype", "text",
                "text", Map.of("content", content)
        );

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("钉钉发送失败: code=" + response.code() + ", body=" + respBody);
            }
            log.info("钉钉消息发送成功");
            NotificationSendResult result = buildSuccessResult(body, Map.of("raw", respBody), respBody);
            applyBilling(result, BigDecimal.ZERO, "CNY", "message", 1);
            return result;
        }
    }

    private String buildContent(NotificationMessage message) {
        return composePlainText(message, "预览链接：");
    }
}
