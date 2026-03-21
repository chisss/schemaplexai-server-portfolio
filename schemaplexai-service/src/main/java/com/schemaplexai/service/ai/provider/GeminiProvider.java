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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Google Gemini 模型适配器
 *
 * <p>POST {config.baseUrl}/v1beta/models/{modelId}:generateContent?key={apiKey}
 * <p>连接参数从 {@link AiModelConfig} 动态获取，不依赖静态配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GeminiProvider implements AIProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 基础客户端，共享连接池和线程池 */
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient();

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "gemini";
    }

    @Override
    public ChatResponse chatWithHistory(String systemPrompt, List<ChatMessage> messages, AiModelConfig config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            log.warn("Gemini API Key 未配置: modelId={}", config.getModelId());
            return ChatResponse.error("Gemini API Key 未配置，请在系统配置中设置");
        }

        log.info("调用 Gemini API: modelId={}, messages={}", config.getModelId(), messages.size());

        try {
            ObjectNode body = objectMapper.createObjectNode();

            // systemInstruction（Gemini 特有字段）
            if (StringUtils.hasText(systemPrompt)) {
                ObjectNode sysInstr = body.putObject("systemInstruction");
                ArrayNode parts = sysInstr.putArray("parts");
                parts.addObject().put("text", systemPrompt);
            }

            // contents：Gemini 角色只有 user / model（不是 assistant）
            ArrayNode contents = body.putArray("contents");
            for (ChatMessage msg : messages) {
                ObjectNode c = contents.addObject();
                c.put("role", "assistant".equals(msg.getRole()) ? "model" : "user");
                ArrayNode parts = c.putArray("parts");
                parts.addObject().put("text", msg.getContent());
            }

            // 生成配置
            ObjectNode genConfig = body.putObject("generationConfig");
            genConfig.put("maxOutputTokens", config.getMaxTokens());

            String bodyStr = objectMapper.writeValueAsString(body);
            String encodedKey = URLEncoder.encode(config.getApiKey(), StandardCharsets.UTF_8);
            String url = config.getBaseUrl() + "/v1beta/models/"
                    + config.getModelId() + ":generateContent?key=" + encodedKey;

            Request request = new Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(bodyStr, JSON))
                    .build();

            try (Response resp = clientFor(config.getTimeoutSeconds()).newCall(request).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.error("Gemini API 响应错误: status={}, body={}", resp.code(), respBody);
                    return ChatResponse.error("Gemini API 错误: HTTP " + resp.code()
                            + " - " + extractErrorMessage(respBody));
                }

                JsonNode root = objectMapper.readTree(respBody);

                // candidates[0].content.parts[0].text
                String content = root.path("candidates").path(0)
                        .path("content").path("parts").path(0)
                        .path("text").asText("");

                // finishReason: STOP / MAX_TOKENS / SAFETY
                String finishReason = root.path("candidates").path(0)
                        .path("finishReason").asText("STOP");
                String stopReason = "MAX_TOKENS".equals(finishReason) ? "max_tokens" : "end_turn";

                JsonNode usage = root.path("usageMetadata");
                long inputTokens = usage.path("promptTokenCount").asLong(0);
                long outputTokens = usage.path("candidatesTokenCount").asLong(0);

                log.info("Gemini 响应完成: modelId={}, inputTokens={}, outputTokens={}, stopReason={}",
                        config.getModelId(), inputTokens, outputTokens, stopReason);

                return ChatResponse.success(content, inputTokens, outputTokens, stopReason);
            }
        } catch (Exception e) {
            log.error("Gemini API 调用异常: modelId={}", config.getModelId(), e);
            return ChatResponse.error("Gemini API 调用异常: " + e.getMessage());
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
