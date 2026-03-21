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
 * Claude AI 模型适配器（Anthropic API）
 *
 * <p>POST {config.baseUrl}/v1/messages
 * <p>连接参数从 {@link AiModelConfig} 动态获取，不依赖静态配置。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeProvider implements AIProvider {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * 基础客户端，子客户端通过 newBuilder() 共享连接池和线程池，仅覆盖超时配置
     */
    private static final OkHttpClient BASE_CLIENT = new OkHttpClient();

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "claude";
    }

    @Override
    public ChatResponse chatWithHistory(String systemPrompt, List<ChatMessage> messages, AiModelConfig config) {
        if (!StringUtils.hasText(config.getApiKey())) {
            log.warn("Claude API Key 未配置: modelId={}", config.getModelId());
            return ChatResponse.error("Claude API Key 未配置，请在系统配置中设置");
        }

        log.info("调用 Claude API: modelId={}, messages={}", config.getModelId(), messages.size());

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", config.getModelId());
            body.put("max_tokens", config.getMaxTokens());

            // system prompt 作为独立字段（Anthropic 特有格式）
            if (StringUtils.hasText(systemPrompt)) {
                body.put("system", systemPrompt);
            }

            ArrayNode msgArray = body.putArray("messages");
            for (ChatMessage msg : messages) {
                ObjectNode m = msgArray.addObject();
                m.put("role", msg.getRole());
                m.put("content", msg.getContent());
            }

            String bodyStr = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(config.getBaseUrl() + "/v1/messages")
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .post(RequestBody.create(bodyStr, JSON))
                    .build();

            try (Response resp = clientFor(config.getTimeoutSeconds()).newCall(request).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.error("Claude API 响应错误: status={}, body={}", resp.code(), respBody);
                    return ChatResponse.error("Claude API 错误: HTTP " + resp.code()
                            + " - " + extractErrorMessage(respBody));
                }

                JsonNode root = objectMapper.readTree(respBody);

                // content[0].text（type=text 块）
                String content = "";
                JsonNode contentArr = root.path("content");
                if (contentArr.isArray() && !contentArr.isEmpty()) {
                    JsonNode firstBlock = contentArr.get(0);
                    if ("text".equals(firstBlock.path("type").asText())) {
                        content = firstBlock.path("text").asText("");
                    }
                }

                JsonNode usage = root.path("usage");
                long inputTokens = usage.path("input_tokens").asLong(0);
                long outputTokens = usage.path("output_tokens").asLong(0);
                String stopReason = root.path("stop_reason").asText("end_turn");

                log.info("Claude 响应完成: modelId={}, inputTokens={}, outputTokens={}, stopReason={}",
                        config.getModelId(), inputTokens, outputTokens, stopReason);

                return ChatResponse.success(content, inputTokens, outputTokens, stopReason);
            }
        } catch (Exception e) {
            log.error("Claude API 调用异常: modelId={}", config.getModelId(), e);
            return ChatResponse.error("Claude API 调用异常: " + e.getMessage());
        }
    }

    /**
     * 基于模型配置的超时秒数构建 OkHttpClient（共享连接池）
     */
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
            JsonNode errorNode = root.path("error").path("message");
            if (!errorNode.isMissingNode()) return errorNode.asText();
        } catch (Exception ignored) {
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }
}
