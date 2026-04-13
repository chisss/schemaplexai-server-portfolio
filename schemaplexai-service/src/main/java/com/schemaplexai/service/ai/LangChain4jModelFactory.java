package com.schemaplexai.service.ai;

import com.schemaplexai.service.ai.http.LangChainOkHttpClientBuilder;
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
        Duration timeout = resolveTimeout(config);
        log.info("创建 LangChain4j 模型实例: provider={}, protocol={}, modelId={}, baseUrl={}",
                config.getProvider(), config.getProtocol(), config.getModelId(), config.getBaseUrl());
        return switch (config.getProtocol()) {
            case AiModelConfig.PROTOCOL_ANTHROPIC -> AnthropicChatModel.builder()
                    .apiKey(config.getApiKey())
                    .modelName(config.getModelId())
                    .maxTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .baseUrl(config.getBaseUrl())
                    .build();
            case AiModelConfig.PROTOCOL_GEMINI -> GoogleAiGeminiChatModel.builder()
                    .apiKey(config.getApiKey())
                    .baseUrl(config.getBaseUrl())
                    .modelName(config.getModelId())
                    .maxOutputTokens(config.getMaxTokens())
                    .timeout(timeout)
                    .build();
            default -> buildOpenAiCompatibleModel(config, timeout);
        };
    }

    private ChatModel buildOpenAiCompatibleModel(AiModelConfig config, Duration timeout) {
        OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelId())
                .timeout(timeout);
        if (shouldUseLegacyMaxTokens(config)) {
            builder.maxTokens(config.getMaxTokens());
        } else {
            builder.maxCompletionTokens(config.getMaxTokens());
        }
        if (shouldUseOkHttpClient(config)) {
            builder.httpClientBuilder(new LangChainOkHttpClientBuilder());
        }
        return builder.build();
    }

    private boolean shouldUseOkHttpClient(AiModelConfig config) {
        if (config == null || !AiModelConfig.PROTOCOL_OPENAI.equals(config.getProtocol())) {
            return false;
        }
        return "anthropic".equals(config.getProvider())
                || "claude".equals(config.getProvider())
                || (config.getBaseUrl() != null && config.getBaseUrl().contains("/claude"));
    }

    boolean shouldUseLegacyMaxTokens(AiModelConfig config) {
        if (config == null || !AiModelConfig.PROTOCOL_OPENAI.equals(config.getProtocol())) {
            return false;
        }
        return "anthropic".equals(config.getProvider())
                || "claude".equals(config.getProvider())
                || (config.getBaseUrl() != null && config.getBaseUrl().contains("/claude"));
    }

    Duration resolveTimeout(AiModelConfig config) {
        int timeoutSeconds = config != null && config.getTimeoutSeconds() > 0
                ? config.getTimeoutSeconds() : 60;
        return Duration.ofSeconds(Math.max(timeoutSeconds, 1));
    }
}
