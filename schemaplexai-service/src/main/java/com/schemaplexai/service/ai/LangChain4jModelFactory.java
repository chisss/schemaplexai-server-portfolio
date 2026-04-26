package com.schemaplexai.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schemaplexai.service.event.AiModelConfigChangedEvent;
import com.schemaplexai.service.ai.http.LangChainOkHttpClientBuilder;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * LangChain4j 模型工厂 — 动态实例化并缓存 {@link ChatModel}
 *
 * <p>从 {@link AiModelConfig}（DB 动态加载）编程式构建模型实例，不依赖 application.yml 静态配置。
 * 同一配置（provider + modelId + baseUrl + apiKey）复用同一 ChatModel 实例，避免频繁创建。
 */
@Slf4j
@Component
public class LangChain4jModelFactory {

    private final Cache<String, ChatModel> modelCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(100)
            .build();

    private final Cache<String, StreamingChatModel> streamingModelCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(30))
            .maximumSize(100)
            .build();

    /**
     * 获取或创建 ChatModel 实例
     */
    public ChatModel getOrCreate(AiModelConfig config) {
        return modelCache.get(config.cacheKey(), key -> buildModel(config));
    }

    /**
     * 获取或创建 StreamingChatModel 实例（用于流式输出）
     */
    public StreamingChatModel getOrCreateStreaming(AiModelConfig config) {
        return streamingModelCache.get("stream:" + config.cacheKey(), key -> buildStreamingModel(config));
    }

    @EventListener
    public void onModelConfigChanged(AiModelConfigChangedEvent event) {
        invalidateAll();
    }

    public void invalidateAll() {
        modelCache.invalidateAll();
        streamingModelCache.invalidateAll();
    }

    private ChatModel buildModel(AiModelConfig config) {
        Duration timeout = resolveTimeout(config);
        log.info("创建 LangChain4j 模型实例: provider={}, protocol={}, modelId={}, reasoning={}",
                config.getProvider(), config.getProtocol(), config.getModelId(), config.getReasoningStrength());
        return switch (config.getProtocol()) {
            case AiModelConfig.PROTOCOL_ANTHROPIC -> {
                var builder = AnthropicChatModel.builder()
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelId())
                        .timeout(timeout)
                        .baseUrl(config.getBaseUrl());
                applyAnthropicThinking(builder, config);
                yield builder.build();
            }
            case AiModelConfig.PROTOCOL_GEMINI -> {
                var builder = GoogleAiGeminiChatModel.builder()
                        .apiKey(config.getApiKey())
                        .baseUrl(config.getBaseUrl())
                        .modelName(config.getModelId())
                        .maxOutputTokens(config.getMaxTokens())
                        .timeout(timeout);
                applyGeminiThinking(builder, config);
                yield builder.build();
            }
            default -> buildOpenAiCompatibleModel(config, timeout);
        };
    }

    private StreamingChatModel buildStreamingModel(AiModelConfig config) {
        Duration timeout = resolveTimeout(config);
        log.info("创建 LangChain4j 流式模型实例: provider={}, protocol={}, modelId={}, reasoning={}",
                config.getProvider(), config.getProtocol(), config.getModelId(), config.getReasoningStrength());
        return switch (config.getProtocol()) {
            case AiModelConfig.PROTOCOL_ANTHROPIC -> {
                var builder = AnthropicStreamingChatModel.builder()
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelId())
                        .timeout(timeout)
                        .baseUrl(config.getBaseUrl());
                applyAnthropicStreamingThinking(builder, config);
                yield builder.build();
            }
            case AiModelConfig.PROTOCOL_GEMINI -> {
                var builder = GoogleAiGeminiStreamingChatModel.builder()
                        .apiKey(config.getApiKey())
                        .baseUrl(config.getBaseUrl())
                        .modelName(config.getModelId())
                        .timeout(timeout);
                applyGeminiStreamingThinking(builder, config);
                yield builder.build();
            }
            default -> buildOpenAiCompatibleStreamingModel(config, timeout);
        };
    }

    private StreamingChatModel buildOpenAiCompatibleStreamingModel(AiModelConfig config, Duration timeout) {
        OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelId())
                .timeout(timeout);
        if (shouldUseLegacyMaxTokens(config)) {
            builder.maxTokens(config.getMaxTokens());
        } else {
            builder.maxCompletionTokens(config.getMaxTokens());
        }
        applyOpenAiReasoning(builder, config);
        return builder.build();
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
        applyOpenAiReasoning(builder, config);
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

    // ── Thinking / Reasoning 参数适配 ──

    private void applyAnthropicThinking(AnthropicChatModel.AnthropicChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            builder.maxTokens(config.getMaxTokens());
            return;
        }
        int budget = resolveAnthropicThinkingBudget(config.getReasoningStrength());
        int maxTokens = Math.max(config.getMaxTokens(), budget + 1024);
        builder.maxTokens(maxTokens)
                .thinkingType("enabled")
                .thinkingBudgetTokens(budget)
                .returnThinking(true);
    }

    private void applyAnthropicStreamingThinking(AnthropicStreamingChatModel.AnthropicStreamingChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            builder.maxTokens(config.getMaxTokens());
            return;
        }
        int budget = resolveAnthropicThinkingBudget(config.getReasoningStrength());
        int maxTokens = Math.max(config.getMaxTokens(), budget + 1024);
        builder.maxTokens(maxTokens)
                .thinkingType("enabled")
                .thinkingBudgetTokens(budget)
                .returnThinking(true);
    }

    private int resolveAnthropicThinkingBudget(String reasoningStrength) {
        return switch (reasoningStrength != null ? reasoningStrength.toLowerCase() : "") {
            case "low" -> 5000;
            case "high" -> 30000;
            default -> 10000;
        };
    }

    private void applyGeminiThinking(GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            return;
        }
        builder.thinkingConfig(buildGeminiThinkingConfig(config.getReasoningStrength()))
                .returnThinking(true);
    }

    private void applyGeminiStreamingThinking(GoogleAiGeminiStreamingChatModel.GoogleAiGeminiStreamingChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            return;
        }
        builder.thinkingConfig(buildGeminiThinkingConfig(config.getReasoningStrength()))
                .returnThinking(true);
    }

    private GeminiThinkingConfig buildGeminiThinkingConfig(String reasoningStrength) {
        GeminiThinkingConfig.GeminiThinkingLevel level = switch (reasoningStrength != null ? reasoningStrength.toLowerCase() : "") {
            case "low" -> GeminiThinkingConfig.GeminiThinkingLevel.LOW;
            case "high" -> GeminiThinkingConfig.GeminiThinkingLevel.HIGH;
            default -> GeminiThinkingConfig.GeminiThinkingLevel.MEDIUM;
        };
        return GeminiThinkingConfig.builder()
                .includeThoughts(true)
                .thinkingLevel(level)
                .build();
    }

    private void applyOpenAiReasoning(OpenAiChatModel.OpenAiChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            return;
        }
        builder.reasoningEffort(config.getReasoningStrength().toLowerCase());
    }

    private void applyOpenAiReasoning(OpenAiStreamingChatModel.OpenAiStreamingChatModelBuilder builder, AiModelConfig config) {
        if (!config.isReasoningSupported()) {
            return;
        }
        builder.reasoningEffort(config.getReasoningStrength().toLowerCase());
    }
}
