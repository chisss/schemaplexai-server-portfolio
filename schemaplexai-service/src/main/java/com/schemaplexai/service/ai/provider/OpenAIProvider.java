package com.schemaplexai.service.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.service.ai.AIProvider;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.ChatMessage;
import com.schemaplexai.service.ai.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容模型适配器
 *
 * <p>POST {config.baseUrl}/v1/chat/completions
 * <p>适用于 OpenAI / DeepSeek / Kimi / ChatGPT 等 OpenAI 兼容协议。
 * <p>连接参数从 {@link AiModelConfig} 动态获取，不依赖静态配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAIProvider implements AIProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 基础客户端，共享连接池和线程池 */
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient();

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "openai";
    }

    @Override
    public ChatResponse chatWithHistory(String systemPrompt, List<ChatMessage> messages, AiModelConfig config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            log.warn("OpenAI API Key 未配置: modelId={}", config.getModelId());
            return ChatResponse.error("OpenAI API Key 未配置，请在系统配置中设置");
        }

        log.info("调用 OpenAI API: modelId={}, messages={}", config.getModelId(), messages.size());

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.getModelId());
            body.put("max_tokens", config.getMaxTokens());

            // OpenAI 将 system 作为 messages 数组中的第一条
            ArrayNode msgArray = body.putArray("messages");
            if (StringUtils.hasText(systemPrompt)) {
                ObjectNode sys = msgArray.addObject();
                sys.put("role", "system");
                sys.put("content", systemPrompt);
            }
            for (ChatMessage msg : messages) {
                ObjectNode m = msgArray.addObject();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
            }

            String bodyStr = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/chat/completions")
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(bodyStr, JSON))
                    .build();

            try (Response resp = clientFor(config.getTimeoutSeconds()).newCall(request).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.error("OpenAI API 响应错误: status={}, body={}", resp.code(), respBody);
                    return ChatResponse.error("OpenAI API 错误: HTTP " + resp.code()
                            + " - " + extractErrorMessage(respBody));
                }

                JsonNode root = objectMapper.readTree(respBody);

                // choices[0].message.content
                String content = root.path("choices").path(0)
                        .path("message").path("content").asText("");

                // finish_reason → 统一 stopReason
                String finishReason = root.path("choices").path(0)
                        .path("finish_reason").asText("stop");
                String stopReason = "length".equals(finishReason) ? "max_tokens" : "stop";

                JsonNode usage = root.path("usage");
                long inputTokens = usage.path("prompt_tokens").asLong(0);
                long outputTokens = usage.path("completion_tokens").asLong(0);

                log.info("OpenAI 响应完成: modelId={}, inputTokens={}, outputTokens={}, stopReason={}",
                        config.getModelId(), inputTokens, outputTokens, stopReason);

                return ChatResponse.success(content, inputTokens, outputTokens, stopReason);
            }
        } catch (Exception e) {
            log.error("OpenAI API 调用异常: modelId={}", config.getModelId(), e);
            return ChatResponse.error("OpenAI API 调用异常: " + e.getMessage());
        }
    }

    private OkHttpClient clientFor(int timeoutSeconds) {
        return BASE_CLIENT.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(Math.max(timeoutSeconds, 60), TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    private String extractErrorMessage(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode msg = root.path("error").path("message");
            if (!msg.isMissingNode()) return msg.asText();
        } catch (Exception ignored) {
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
