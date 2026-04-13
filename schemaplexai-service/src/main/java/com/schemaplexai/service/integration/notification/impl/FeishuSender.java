package com.schemaplexai.service.integration.notification.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.integration.notification.AbstractNotificationSender;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
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
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.math.BigDecimal;

/**
 * 飞书应用机器人消息发送器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeishuSender extends AbstractNotificationSender {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public String getChannelType() {
        return "feishu";
    }

    @Override
    protected NotificationSendResult doSend(Map<String, Object> config, NotificationMessage message) throws Exception {
        String appId = resolveConfig(config, "app_id", "appId", "APP_ID");
        String appSecret = resolveConfig(config, "app_secret", "appSecret", "App Secret");
        String receiveId = resolveConfig(config, "receive_id", "receiveId", "chat_id", "会话ID");
        String receiveIdType = resolveConfig(config, "receive_id_type", "receiveIdType");
        if (!StringUtils.hasText(receiveIdType)) {
            receiveIdType = "chat_id";
        }

        String accessToken = requestTenantAccessToken(appId, appSecret);
        Map<String, Object> body = new HashMap<>();
        body.put("receive_id", receiveId);
        body.put("msg_type", "text");
        body.put("content", objectMapper.writeValueAsString(Map.of("text", buildContent(message))));
        body.put("uuid", UUID.randomUUID().toString());

        String url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=" + receiveIdType;
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, JSON_TYPE))
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                        "飞书发送失败: http=" + response.code() + ", body=" + respBody);
            }
            Map<String, Object> responseBody = objectMapper.readValue(respBody, Map.class);
            Object code = responseBody.get("code");
            if (code != null && !"0".equals(String.valueOf(code))) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                        "飞书发送失败: code=" + code + ", msg=" + responseBody.get("msg"));
            }
            String providerMessageId = null;
            Object data = responseBody.get("data");
            if (data instanceof Map<?, ?> dataMap && dataMap.get("message_id") != null) {
                providerMessageId = String.valueOf(dataMap.get("message_id"));
            }
            log.info("飞书消息发送成功: receiveIdType={}, receiveId={}", receiveIdType, receiveId);
            NotificationSendResult result = buildSuccessResult(body, responseBody, respBody);
            result.setProviderMessageId(providerMessageId);
            applyBilling(result, BigDecimal.ZERO, "CNY", "message", 1);
            return result;
        }
    }

    private String requestTenantAccessToken(String appId, String appSecret) throws Exception {
        Map<String, Object> tokenRequest = Map.of(
                "app_id", appId,
                "app_secret", appSecret
        );
        Request request = new Request.Builder()
                .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                .post(RequestBody.create(objectMapper.writeValueAsString(tokenRequest), JSON_TYPE))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String respBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                        "获取飞书 tenant_access_token 失败: http=" + response.code() + ", body=" + respBody);
            }
            Map<String, Object> responseBody = objectMapper.readValue(respBody, Map.class);
            Object code = responseBody.get("code");
            if (code != null && !"0".equals(String.valueOf(code))) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED,
                        "获取飞书 tenant_access_token 失败: code=" + code + ", msg=" + responseBody.get("msg"));
            }
            return String.valueOf(responseBody.get("tenant_access_token"));
        }
    }

    private String resolveConfig(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        throw new IllegalArgumentException("飞书配置缺少字段: " + String.join("/", keys));
    }

    private String buildContent(NotificationMessage message) {
        return composePlainText(message, "预览链接：");
    }
}
