package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token 估算支持。
 *
 * <p>优先使用 LangChain4j 的 {@link OpenAiTokenCountEstimator}，失败时退化为基于字符数的近似估算。
 */
final class TokenEstimatorSupport {

    private static final int DEFAULT_CHARS_PER_TOKEN = 4;

    private final Map<String, TokenCountEstimator> estimatorCache = new ConcurrentHashMap<>();
    private final TokenCountEstimator fallbackEstimator = new ApproximateTokenCountEstimator();

    int estimateText(AiModelConfig config, String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        try {
            return resolveEstimator(config).estimateTokenCountInText(text);
        } catch (Exception ex) {
            return approximateTokens(text.length());
        }
    }

    int estimateMessage(AiModelConfig config, ChatMessage message) {
        if (message == null) {
            return 0;
        }
        try {
            return resolveEstimator(config).estimateTokenCountInMessage(message);
        } catch (Exception ex) {
            return approximateTokens(String.valueOf(message).length());
        }
    }

    int estimateMessages(AiModelConfig config, Iterable<ChatMessage> messages) {
        if (messages == null) {
            return 0;
        }
        try {
            return resolveEstimator(config).estimateTokenCountInMessages(messages);
        } catch (Exception ex) {
            int total = 0;
            for (ChatMessage message : messages) {
                total += estimateMessage(config, message);
            }
            return total;
        }
    }

    int estimateObjectTokens(AiModelConfig config, Object value) {
        if (value == null) {
            return 0;
        }
        return estimateText(config, String.valueOf(value));
    }

    TokenCountEstimator resolveEstimator(AiModelConfig config) {
        String modelId = config != null && StringUtils.hasText(config.getModelId())
                ? config.getModelId().trim()
                : "gpt-4o-mini";
        return estimatorCache.computeIfAbsent(modelId, this::createEstimator);
    }

    int approximateTokens(int charCount) {
        if (charCount <= 0) {
            return 0;
        }
        return Math.max(1, (charCount + DEFAULT_CHARS_PER_TOKEN - 1) / DEFAULT_CHARS_PER_TOKEN);
    }

    private TokenCountEstimator createEstimator(String modelId) {
        try {
            return new OpenAiTokenCountEstimator(modelId);
        } catch (Exception ex) {
            return fallbackEstimator;
        }
    }

    private static final class ApproximateTokenCountEstimator implements TokenCountEstimator {

        @Override
        public int estimateTokenCountInText(String text) {
            return text == null ? 0 : Math.max(1, (text.length() + DEFAULT_CHARS_PER_TOKEN - 1) / DEFAULT_CHARS_PER_TOKEN);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            return message == null ? 0 : estimateTokenCountInText(String.valueOf(message));
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            if (messages == null) {
                return 0;
            }
            int total = 0;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }
}
