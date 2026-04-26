package com.schemaplexai.service.workflow;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 产物场景识别器
 *
 * <p>用于识别当前节点是否面向客户交付，避免把仓库审计类提示词误注入到客户文档。
 */
public final class ArtifactSceneResolver {

    private ArtifactSceneResolver() {}

    /**
     * 根据节点配置识别客户交付场景。
     */
    public static boolean isCustomerDelivery(Map<String, Object> config) {
        String docType = inferArtifactDocType(config);
        return StringUtils.hasText(docType) && "delivery".equalsIgnoreCase(docType);
    }

    /**
     * 根据提示词正文识别客户交付场景。
     */
    public static boolean isCustomerDeliveryPrompt(String promptText) {
        if (!StringUtils.hasText(promptText)) {
            return false;
        }
        String normalized = promptText.toLowerCase(Locale.ROOT);
        return normalized.contains("目标产物: deliveries/")
                || normalized.contains("deliveries/")
                || normalized.contains("customer-demo")
                || normalized.contains("-delivery.md")
                || promptText.contains("客户演示方案")
                || promptText.contains("方案交付")
                || promptText.contains("交付文档");
    }

    /**
     * 根据节点配置推断文档类型。
     */
    public static String inferArtifactDocType(Map<String, Object> config) {
        String configuredDocType = readString(config, "artifactDocType");
        if (StringUtils.hasText(configuredDocType)) {
            return configuredDocType.trim();
        }
        if (StringUtils.hasText(readString(config, "artifactDeliveryType"))) {
            return "delivery";
        }
        String outputPath = readString(config, "artifactOutputPath");
        if (looksLikeDeliveryPath(outputPath)) {
            return "delivery";
        }
        String outputVariableKey = readString(config, "outputVariableKey");
        if (looksLikeDeliveryVariable(outputVariableKey)) {
            return "delivery";
        }
        String artifactTitle = readString(config, "artifactTitle");
        if (looksLikeDeliveryTitle(artifactTitle)) {
            return "delivery";
        }
        return null;
    }

    private static boolean looksLikeDeliveryPath(String outputPath) {
        if (!StringUtils.hasText(outputPath)) {
            return false;
        }
        String normalized = outputPath.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("deliveries/")
                || normalized.contains("/deliveries/")
                || normalized.endsWith("-delivery.md")
                || normalized.contains("customer-demo");
    }

    private static boolean looksLikeDeliveryVariable(String outputVariableKey) {
        if (!StringUtils.hasText(outputVariableKey)) {
            return false;
        }
        return outputVariableKey.toLowerCase(Locale.ROOT).contains("delivery");
    }

    private static boolean looksLikeDeliveryTitle(String artifactTitle) {
        if (!StringUtils.hasText(artifactTitle)) {
            return false;
        }
        return artifactTitle.contains("交付")
                || artifactTitle.contains("演示方案")
                || artifactTitle.contains("客户方案")
                || artifactTitle.toLowerCase(Locale.ROOT).contains("customer demo");
    }

    private static String readString(Map<String, Object> config, String key) {
        if (config == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
