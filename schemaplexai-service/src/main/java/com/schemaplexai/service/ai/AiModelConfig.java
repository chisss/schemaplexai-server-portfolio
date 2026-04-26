package com.schemaplexai.service.ai;

import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.common.util.AesEncryptUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;

/**
 * AI 模型运行时连接配置
 *
 * <p>从 {@code sf_ai_model} 表动态加载，每次执行由 {@link AIModelRouter} 构建，不可变。
 * Provider 实现通过此对象获取 apiKey/baseUrl/modelId 等连接参数，不再依赖 Spring @Value 静态配置。
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class AiModelConfig {

    private static final int DEFAULT_CONTEXT_WINDOW_TOKENS = 32_768;
    private static final int MIN_CONTEXT_WINDOW_TOKENS = 16_384;
    private static final int CONTEXT_WINDOW_OUTPUT_BUFFER_TOKENS = 8_192;

    public static final String PROTOCOL_ANTHROPIC = "anthropic";
    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_GEMINI = "gemini";

    /** 解码后的 API Key（明文） */
    private final String apiKey;

    /** 平台内部模型配置 ID */
    private final String configId;

    /** API Base URL（已去除尾部斜杠），如 https://api.anthropic.com */
    private final String baseUrl;

    /** 实际模型标识，如 claude-3-5-sonnet-20241022 / gpt-4o */
    private final String modelId;

    /** 提供商标识（小写），如 claude / openai / gemini / deepseek */
    private final String provider;

    /** 实际请求协议，如 anthropic / openai / gemini */
    private final String protocol;

    /** 输入单价 */
    private final BigDecimal inputPrice;

    /** 输出单价 */
    private final BigDecimal outputPrice;

    /** 最大输出 Token 数 */
    private final int maxTokens;

    /** 模型上下文窗口 Token 数（用于动态预算规划） */
    private final int contextWindowTokens;

    /** 模型累计 Tokens 限额 */
    private final Integer maxQuotaTokens;

    /** HTTP 请求超时秒数（read timeout） */
    private final int timeoutSeconds;

    /** 模型调用重试次数 */
    private final int retryCount;

    /** 模型调用重试间隔（秒） */
    private final int retryIntervalSeconds;

    /** 支持的推理强度等级，逗号分隔，如 "low,medium,high" */
    private final String supportedReasoningEfforts;

    /** 当前请求的推理强度：low / medium / high */
    private final String reasoningStrength;

    /** 是否支持多模态输入（图片等视觉能力） */
    private final boolean multimodal;

    /**
     * 从 sf_ai_model 实体构建运行时配置
     */
    public static AiModelConfig from(AiModel model) {
        return from(model, null);
    }

    public static AiModelConfig from(AiModel model, String reasoningStrength) {
        String provider = normalizeProvider(model);
        String protocol = resolveProtocol(model, provider);

        String baseUrl = normalizeBaseUrl(
                StringUtils.hasText(model.getBaseUrl()) ? model.getBaseUrl() : defaultBaseUrl(provider),
                provider,
                protocol
        );

        String rawKey = model.getApiKeyEncrypted();
        String apiKey = StringUtils.hasText(rawKey) ? AesEncryptUtil.decrypt(rawKey) : "";

        return AiModelConfig.builder()
                .apiKey(apiKey)
                .configId(model.getId())
                .baseUrl(baseUrl)
                .modelId(model.getModelId())
                .provider(provider)
                .protocol(protocol)
                .inputPrice(model.getInputPrice())
                .outputPrice(model.getOutputPrice())
                .maxTokens(model.getMaxTokens() != null ? model.getMaxTokens() : 4096)
                .contextWindowTokens(resolveContextWindowTokens(model))
                .maxQuotaTokens(model.getMaxQuotaTokens())
                .timeoutSeconds(model.getTimeoutSeconds() != null ? model.getTimeoutSeconds() : 60)
                .retryCount(model.getRetryCount() != null ? Math.max(model.getRetryCount(), 0) : 0)
                .retryIntervalSeconds(model.getRetryIntervalSeconds() != null
                        ? Math.max(model.getRetryIntervalSeconds(), 1) : 1)
                .supportedReasoningEfforts(model.getSupportedReasoningEfforts())
                .reasoningStrength(reasoningStrength)
                .multimodal(Boolean.TRUE.equals(model.getMultimodal()))
                .build();
    }

    public static String resolveProtocol(AiModel model) {
        return resolveProtocol(model, normalizeProvider(model));
    }

    public static String normalizeBaseUrl(String baseUrl, String provider, String protocol) {
        if (!StringUtils.hasText(baseUrl)) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        trimmed = stripKnownEndpointSuffix(trimmed);
        if (PROTOCOL_ANTHROPIC.equals(protocol)) {
            if (!trimmed.endsWith("/v1")) {
                trimmed = trimmed + "/v1";
            }
            return trimmed;
        }
        if (PROTOCOL_OPENAI.equals(protocol) && !trimmed.endsWith("/v1")) {
            trimmed = trimmed + "/v1";
        }
        return trimmed;
    }

    private static String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "anthropic", "claude" -> "https://api.anthropic.com";
            case "gemini", "google" -> "https://generativelanguage.googleapis.com";
            case "kimi" -> "https://api.moonshot.cn";
            case "deepseek" -> "https://api.deepseek.com";
            default -> "https://api.openai.com";
        };
    }

    /**
     * 生成模型实例缓存键（用于 LangChain4jModelFactory 缓存）
     * 基于 provider + modelId + baseUrl + apiKey 哈希
     */
    public String cacheKey() {
        return provider + "|" + protocol + "|" + modelId + "|" + baseUrl + "|" + maxTokens + "|"
                + contextWindowTokens + "|"
                + timeoutSeconds + "|" + apiKey.hashCode()
                + "|" + (reasoningStrength != null ? reasoningStrength : "");
    }

    /**
     * 当前推理强度是否被该模型支持
     */
    public boolean isReasoningSupported() {
        return StringUtils.hasText(supportedReasoningEfforts)
                && StringUtils.hasText(reasoningStrength)
                && supportedReasoningEfforts.toLowerCase().contains(reasoningStrength.toLowerCase());
    }

    public int resolvedContextWindowTokens() {
        if (contextWindowTokens > 0) {
            return contextWindowTokens;
        }
        return Math.max(maxTokens * 6, 16_384);
    }

    private static String normalizeProvider(AiModel model) {
        return model != null && StringUtils.hasText(model.getProvider())
                ? model.getProvider().trim().toLowerCase()
                : PROTOCOL_OPENAI;
    }

    private static String resolveProtocol(AiModel model, String provider) {
        if (model != null && StringUtils.hasText(model.getProtocol())) {
            return normalizeProtocol(model.getProtocol());
        }
        String explicitProtocol = resolveExplicitProtocol(model != null ? model.getDefaultParams() : null);
        if (StringUtils.hasText(explicitProtocol)) {
            return explicitProtocol;
        }
        if ("gemini".equals(provider) || "google".equals(provider)) {
            return PROTOCOL_GEMINI;
        }
        if ("anthropic".equals(provider) || "claude".equals(provider)) {
            String baseUrl = model != null ? model.getBaseUrl() : null;
            if (usesAnthropicMessagesProtocol(baseUrl)) {
                return PROTOCOL_ANTHROPIC;
            }
            if (looksLikeOpenAiCompatibleClaudeProxy(baseUrl)) {
                return PROTOCOL_OPENAI;
            }
            return PROTOCOL_ANTHROPIC;
        }
        return PROTOCOL_OPENAI;
    }

    private static String normalizeProtocol(String protocol) {
        String normalized = protocol.trim().toLowerCase();
        return switch (normalized) {
            case "anthropic", "messages", "anthropic-compatible", "anthropic_compatible" -> PROTOCOL_ANTHROPIC;
            case "openai", "chat_completions", "chat-completions", "openai-compatible", "openai_compatible" ->
                    PROTOCOL_OPENAI;
            case "gemini", "google" -> PROTOCOL_GEMINI;
            default -> PROTOCOL_OPENAI;
        };
    }

    private static String resolveExplicitProtocol(Map<String, Object> defaultParams) {
        if (defaultParams == null || defaultParams.isEmpty()) {
            return "";
        }
        Object rawValue = firstPresent(defaultParams, "protocol", "apiProtocol", "compatibilityProtocol");
        if (rawValue == null) {
            return "";
        }
        String normalized = String.valueOf(rawValue).trim().toLowerCase();
        return switch (normalized) {
            case "anthropic", "messages", "anthropic-compatible", "anthropic_compatible" -> PROTOCOL_ANTHROPIC;
            case "openai", "chat_completions", "chat-completions", "openai-compatible", "openai_compatible" ->
                    PROTOCOL_OPENAI;
            case "gemini", "google" -> PROTOCOL_GEMINI;
            default -> "";
        };
    }

    private static Object firstPresent(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            if (values.containsKey(key) && values.get(key) != null) {
                return values.get(key);
            }
        }
        return null;
    }

    private static boolean usesAnthropicMessagesProtocol(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return true;
        }
        String normalized = baseUrl.trim().toLowerCase();
        return normalized.contains("api.anthropic.com") || normalized.contains("/anthropic");
    }

    private static boolean looksLikeOpenAiCompatibleClaudeProxy(String baseUrl) {
        return StringUtils.hasText(baseUrl) && baseUrl.trim().toLowerCase().contains("/claude");
    }

    private static String stripKnownEndpointSuffix(String baseUrl) {
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
        }
        if (baseUrl.endsWith("/messages")) {
            return baseUrl.substring(0, baseUrl.length() - "/messages".length());
        }
        return baseUrl;
    }

    private static int resolveContextWindowTokens(AiModel model) {
        if (model == null || model.getDefaultParams() == null || model.getDefaultParams().isEmpty()) {
            return fallbackContextWindowTokens(model);
        }
        Object raw = firstPresent(model.getDefaultParams(),
                "contextWindowTokens",
                "context_window",
                "contextWindow",
                "maxContextTokens",
                "max_context_tokens",
                "maxInputTokens",
                "max_input_tokens");
        if (raw == null) {
            return fallbackContextWindowTokens(model);
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(raw).trim());
            return parsed > 0 ? parsed : fallbackContextWindowTokens(model);
        } catch (NumberFormatException ex) {
            return fallbackContextWindowTokens(model);
        }
    }

    private static int fallbackContextWindowTokens(AiModel model) {
        int outputReserve = model != null && model.getMaxTokens() != null
                ? Math.max(model.getMaxTokens(), 0)
                : 0;
        return Math.max(DEFAULT_CONTEXT_WINDOW_TOKENS,
                Math.max(MIN_CONTEXT_WINDOW_TOKENS, outputReserve + CONTEXT_WINDOW_OUTPUT_BUFFER_TOKENS));
    }
}
