package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.ChatMessageText;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 为 TokenWindow 和请求预算提供同一套支持图文的估算。 */
final class TokenEstimatorSupport {

    private static final int DEFAULT_CHARS_PER_TOKEN = 4;
    private static final int PER_MESSAGE_OVERHEAD = 4;
    private static final int OTHER_MEDIA_TOKENS = 1024;

    private final Map<String, TokenCountEstimator> estimatorCache = new ConcurrentHashMap<>();

    int estimateText(AiModelConfig config, String text) {
        return resolveEstimator(config).estimateTokenCountInText(text);
    }

    int estimateMessage(AiModelConfig config, ChatMessage message) {
        return resolveEstimator(config).estimateTokenCountInMessage(message);
    }

    int estimateMessages(AiModelConfig config, Iterable<ChatMessage> messages) {
        return resolveEstimator(config).estimateTokenCountInMessages(messages);
    }

    int estimateObjectTokens(AiModelConfig config, Object value) {
        if (value instanceof ChatMessage message) {
            return estimateMessage(config, message);
        }
        return value == null ? 0 : estimateText(config, String.valueOf(value));
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
        return 1 + (charCount - 1) / DEFAULT_CHARS_PER_TOKEN;
    }

    private TokenCountEstimator createEstimator(String modelId) {
        try {
            return new ContentAwareEstimator(new OpenAiTokenCountEstimator(modelId));
        } catch (IllegalArgumentException ex) {
            return new ContentAwareEstimator(null);
        }
    }

    private final class ContentAwareEstimator implements TokenCountEstimator {

        private final TokenCountEstimator delegate;

        private ContentAwareEstimator(TokenCountEstimator delegate) {
            this.delegate = delegate;
        }

        @Override
        public int estimateTokenCountInText(String text) {
            if (!StringUtils.hasText(text)) {
                return 0;
            }
            if (delegate != null) {
                try {
                    return delegate.estimateTokenCountInText(text);
                } catch (RuntimeException ignored) {
                    // 保持兜底路径可用，不输出原文。
                }
            }
            int multibyte = 0;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) > 127) {
                    multibyte++;
                }
            }
            return multibyte + approximateTokens(text.length() - multibyte);
        }

        @Override
        public int estimateTokenCountInMessage(ChatMessage message) {
            if (message == null) {
                return 0;
            }
            if (message instanceof UserMessage user && !user.hasSingleText()) {
                return estimateContents(user.contents()) + estimateTokenCountInText(user.name());
            }
            if (message instanceof ToolExecutionResultMessage result && !result.hasSingleText()) {
                return estimateContents(result.contents());
            }
            if (delegate != null) {
                try {
                    return delegate.estimateTokenCountInMessage(message);
                } catch (RuntimeException ignored) {
                    // 工具参数可能是未通过校验的 JSON，按文本长度继续估算。
                }
            }
            int total = PER_MESSAGE_OVERHEAD + estimateTokenCountInText(ChatMessageText.textOf(message));
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : ai.toolExecutionRequests()) {
                    total += PER_MESSAGE_OVERHEAD + estimateTokenCountInText(request.name())
                            + estimateTokenCountInText(request.arguments());
                }
            }
            return total;
        }

        private int estimateContents(List<Content> contents) {
            int total = PER_MESSAGE_OVERHEAD;
            for (Content content : contents) {
                total += switch (content) {
                    case TextContent text -> estimateTokenCountInText(text.text());
                    case ImageContent image -> switch (image.detailLevel()) {
                        case LOW -> 256;
                        case MEDIUM -> 512;
                        case HIGH, AUTO -> OTHER_MEDIA_TOKENS;
                        case ULTRA_HIGH -> 2048;
                    };
                    default -> OTHER_MEDIA_TOKENS;
                };
            }
            return total;
        }

        @Override
        public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
            if (messages == null) {
                return 0;
            }
            int total = 3;
            for (ChatMessage message : messages) {
                total += estimateTokenCountInMessage(message);
            }
            return total;
        }
    }
}
