package com.schemaplexai.service.integration.notification.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.integration.notification.NotificationSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Slack Webhook 消息发送器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackSender implements NotificationSender {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "slack";
    }

    @Override
    public String sendTestMessage(Map<String, Object> config) throws Exception {
        String webhookUrl = String.valueOf(config.getOrDefault("webhook_url", ""));
        if (webhookUrl.isBlank()) {
            throw new IllegalArgumentException("Slack webhook_url 未配置");
        }

        // Slack Incoming Webhook 消息格式
        Map<String, Object> body = Map.of(
                "text", "[SchemaPlexAI] 测试消息 - Slack 通知渠道配置验证成功"
        );

        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(json, JSON_TYPE))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("Slack 发送失败: code=" + response.code() + ", body=" + respBody);
            }
            log.info("Slack 测试消息发送成功");
            return respBody;
        }
    }
}
