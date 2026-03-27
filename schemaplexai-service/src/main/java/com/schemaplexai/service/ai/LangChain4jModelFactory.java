package com.schemaplexai.service.ai;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j 模型工厂 — 动态实例化并缓存 {@link ChatModel}
 *
 * <p>从 {@link AiModelConfig}（DB 动态加载）编程式构建模型实例，不依赖 application.yml 静态配置。
 * 同一配置（provider + modelId + baseUrl + apiKey）复用同一 ChatModel 实例，避免频繁创建。
 */
@Slf4j
@Component
public class LangChain4jModelFactory {

    private final ConcurrentHashMap<String, ChatModel> cache = new ConcurrentHashMap<>();

    /**
     * 获取或创建 ChatModel 实例
     */
    public ChatModel getOrCreate(AiModelConfig config) {
        return cache.computeIfAbsent(config.cacheKey(), k -> buildModel(config));
    }

    private ChatModel buildModel(AiModelConfig config) {
        Duration timeout = Duration.ofSeconds(Math.max(config.getTimeoutSeconds(), 60));
        log.info("创建 LangChain4j 模型实例: provider={}, modelId={}", config.getProvider(), config.getModelId());
        return switch (config.getProvider()) {
            case "claude", "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .maxTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .baseUrl(config.getBaseUrl())
                    .build();
            case "gemini", "google" -> GoogleAiGeminiChatModel.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModelId())
                    .maxOutputTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .build();
            default -> OpenAiChatModel.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModelId())
                    .maxCompletionTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .build();
        };
    }
}
