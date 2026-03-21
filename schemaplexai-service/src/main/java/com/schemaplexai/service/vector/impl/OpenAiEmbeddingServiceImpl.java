package com.schemaplexai.service.vector.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.vector.EmbeddingService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI text-embedding-3-small 嵌入实现（维度 1536）
 *
 * <p>激活条件: {@code ai.embedding.provider=openai}（默认激活）
 * <p>若 API Key 为空，自动降级到 {@link MockEmbeddingServiceImpl}
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "openai", matchIfMissing = true)
public class OpenAiEmbeddingServiceImpl implements EmbeddingService {

    private static final int DIMENSION = 1536;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${ai.embedding.api-key:${ai.openai.api-key:}}")
    private String apiKey;

    @Value("${ai.embedding.base-url:https://api.openai.com}")
    private String baseUrl;

    @Value("${ai.embedding.model:text-embedding-3-small}")
    private String model;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MockEmbeddingServiceImpl mockFallback;

    public OpenAiEmbeddingServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.mockFallback = new MockEmbeddingServiceImpl();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("Embedding API Key 未配置，降级到 Mock 实现");
            return mockFallback.embed(text);
        }
        try {
            String json = objectMapper.writeValueAsString(new EmbedRequest(model, text));
            RequestBody body = RequestBody.create(json.getBytes(StandardCharsets.UTF_8), JSON);
            Request request = new Request.Builder()
                    .url(baseUrl + "/v1/embeddings")
                    .post(body)
                    .header("Authorization", "Bearer " + apiKey)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("Embedding API 返回异常: code={}", response.code());
                    return mockFallback.embed(text);
                }
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                JsonNode embeddingNode = root.path("data").get(0).path("embedding");
                float[] vector = new float[DIMENSION];
                for (int i = 0; i < embeddingNode.size() && i < DIMENSION; i++) {
                    vector[i] = (float) embeddingNode.get(i).asDouble();
                }
                return vector;
            }
        } catch (Exception e) {
            log.warn("Embedding API 调用失败，降级到 Mock 实现: {}", e.getMessage());
            return mockFallback.embed(text);
        }
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }

    /** 请求 POJO（Jackson 序列化） */
    record EmbedRequest(String model, String input) {}
}
