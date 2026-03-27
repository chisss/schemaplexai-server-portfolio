package com.schemaplexai.service.ai;

import com.schemaplexai.model.entity.AiModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AI 模型运行时连接配置
 *
 * <p>从 {@code sf_ai_model} 表动态加载，每次执行由 {@link AIModelRouter} 构建，不可变。
 * Provider 实现通过此对象获取 apiKey/baseUrl/modelId 等连接参数，不再依赖 Spring @Value 静态配置。
 */
@Getter
@Builder
@AllArgsConstructor
public class AiModelConfig {

    /** 解码后的 API Key（明文） */
    private final String apiKey;

    /** API Base URL（已去除尾部斜杠），如 https://api.anthropic.com */
    private final String baseUrl;

    /** 实际模型标识，如 claude-3-5-sonnet-20241022 / gpt-4o */
    private final String modelId;

    /** 提供商标识（小写），如 claude / openai / gemini / deepseek */
    private final String provider;

    /** 最大输出 Token 数 */
    private final int maxTokens;

    /** HTTP 请求超时秒数（read timeout） */
    private final int timeoutSeconds;

    /**
     * 从 sf_ai_model 实体构建运行时配置
     */
    public static AiModelConfig from(AiModel model) {
        String provider = StringUtils.hasText(model.getProvider())
                ? model.getProvider().toLowerCase()
                : "openai";

        String baseUrl = normalizeBaseUrl(
                StringUtils.hasText(model.getBaseUrl()) ? model.getBaseUrl() : defaultBaseUrl(provider)
        );

        String rawKey = model.getApiKeyEncrypted();
        String apiKey = StringUtils.hasText(rawKey)
                ? new String(Base64.getDecoder().decode(rawKey), StandardCharsets.UTF_8)
                : "";

        return AiModelConfig.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelId(model.getModelId())
                .provider(provider)
                .maxTokens(model.getMaxTokens() != null ? model.getMaxTokens() : 4096)
                .timeoutSeconds(model.getTimeoutSeconds() != null ? model.getTimeoutSeconds() : 60)
                .build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) return "";
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        // Anthropic 官方 API 根地址（不含 /v1）→ 自动补上 /v1
        // MiniMax / Zhipu 等代理已含 /anthropic 等路径，跳过处理
        if (!trimmed.equals("https://api.anthropic.com") && trimmed.contains("anthropic") || trimmed.contains("claude")) {
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
        return provider + "|" + modelId + "|" + baseUrl + "|" + maxTokens + "|" + timeoutSeconds + "|" + apiKey.hashCode();
    }
}
