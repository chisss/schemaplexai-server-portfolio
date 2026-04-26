package com.schemaplexai.service.ai;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 模型真实健康检查错误分类器
 */
@Component
public class AiModelHealthErrorClassifier {

    public static final String OK = "OK";
    public static final String INVALID_PARAMETER = "INVALID_PARAMETER";
    public static final String NOT_SUPPORT = "NOT_SUPPORT";
    public static final String AUTH_FAILED = "AUTH_FAILED";
    public static final String COOLDOWN = "COOLDOWN";
    public static final String QUOTA_EXCEEDED = "QUOTA_EXCEEDED";
    public static final String NETWORK_EOF = "NETWORK_EOF";
    public static final String MISSING_PRICE_CONFIG = "MISSING_PRICE_CONFIG";
    public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";

    public String classify(Throwable throwable) {
        return classify(throwable != null ? throwable.getMessage() : null);
    }

    public String classify(String message) {
        if (!StringUtils.hasText(message)) {
            return UNKNOWN_ERROR;
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("invalidparameter") || normalized.contains("invalid parameter")) {
            return INVALID_PARAMETER;
        }
        if (normalized.contains("not support") || normalized.contains("unsupported")) {
            return NOT_SUPPORT;
        }
        if (normalized.contains("401") || normalized.contains("403")
                || normalized.contains("unauthorized") || normalized.contains("forbidden")
                || normalized.contains("invalid api key") || normalized.contains("authentication")) {
            return AUTH_FAILED;
        }
        if (normalized.contains("冷却") || normalized.contains("cooldown") || normalized.contains("cool down")) {
            return COOLDOWN;
        }
        if (normalized.contains("quota") || normalized.contains("insufficient") || normalized.contains("限额")) {
            return QUOTA_EXCEEDED;
        }
        if (normalized.contains("eof") || normalized.contains("unexpected end") || normalized.contains("connection reset")) {
            return NETWORK_EOF;
        }
        return UNKNOWN_ERROR;
    }
}
