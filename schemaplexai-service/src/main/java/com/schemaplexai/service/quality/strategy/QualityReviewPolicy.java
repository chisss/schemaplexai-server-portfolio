package com.schemaplexai.service.quality.strategy;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 质量评估运行时策略
 */
public record QualityReviewPolicy(
        String issueType,
        String triggerMode,
        List<String> enabledDimensions,
        Map<String, DimensionConfig> dimensionConfigs,
        List<RuleDefinition> rules
) {

    public static final int DEFAULT_STRICTNESS = 70;
    public static final int DEFAULT_CONFIDENCE_THRESHOLD = 60;
    public static final int DEFAULT_MAX_FINDINGS = 8;

    public QualityReviewPolicy {
        enabledDimensions = immutableDistinct(enabledDimensions);
        dimensionConfigs = dimensionConfigs == null ? Map.of() : Map.copyOf(dimensionConfigs);
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public static QualityReviewPolicy empty(String issueType, String triggerMode) {
        return new QualityReviewPolicy(issueType, triggerMode, List.of(), Map.of(), List.of());
    }

    public boolean isDimensionEnabled(String dimensionCode) {
        if (dimensionCode == null || dimensionCode.isBlank()) {
            return true;
        }
        if (!enabledDimensions.isEmpty() && !enabledDimensions.contains(dimensionCode)) {
            return false;
        }
        return dimensionConfig(dimensionCode).enabled();
    }

    public DimensionConfig dimensionConfig(String dimensionCode) {
        if (dimensionCode == null || dimensionCode.isBlank()) {
            return DimensionConfig.defaultConfig();
        }
        return dimensionConfigs.getOrDefault(dimensionCode, DimensionConfig.defaultConfig());
    }

    public int confidenceThresholdFor(String dimensionCode) {
        return dimensionConfig(dimensionCode).confidenceThreshold();
    }

    public int maxFindingsFor(String dimensionCode) {
        return dimensionConfig(dimensionCode).maxFindings();
    }

    public int overallMaxFindings() {
        if (dimensionConfigs.isEmpty()) {
            return DEFAULT_MAX_FINDINGS;
        }
        int total = dimensionConfigs.values().stream()
                .filter(DimensionConfig::enabled)
                .mapToInt(DimensionConfig::maxFindings)
                .sum();
        if (total <= 0) {
            return DEFAULT_MAX_FINDINGS;
        }
        return Math.max(DEFAULT_MAX_FINDINGS, Math.min(20, total));
    }

    public record DimensionConfig(
            boolean enabled,
            int strictness,
            int confidenceThreshold,
            int maxFindings,
            List<String> focusKeywords,
            boolean blockOnCritical,
            String evaluationPrompt
    ) {

        public DimensionConfig {
            strictness = clamp(strictness, DEFAULT_STRICTNESS);
            confidenceThreshold = clamp(confidenceThreshold, DEFAULT_CONFIDENCE_THRESHOLD);
            maxFindings = maxFindings > 0 ? maxFindings : DEFAULT_MAX_FINDINGS;
            focusKeywords = immutableDistinct(focusKeywords);
            evaluationPrompt = evaluationPrompt == null ? "" : evaluationPrompt.trim();
        }

        public static DimensionConfig defaultConfig() {
            return new DimensionConfig(true, DEFAULT_STRICTNESS, DEFAULT_CONFIDENCE_THRESHOLD,
                    DEFAULT_MAX_FINDINGS, List.of(), true, "");
        }
    }

    public record RuleDefinition(
            String code,
            String name,
            String dimensionCode,
            String severity,
            Integer weight,
            String conditionExpr,
            String promptInstruction,
            List<String> focusKeywords
    ) {

        public RuleDefinition {
            code = code == null ? "" : code.trim();
            name = name == null ? "" : name.trim();
            dimensionCode = dimensionCode == null ? "" : dimensionCode.trim();
            severity = severity == null ? "" : severity.trim();
            weight = weight == null ? 100 : weight;
            conditionExpr = conditionExpr == null ? "" : conditionExpr.trim();
            promptInstruction = promptInstruction == null ? "" : promptInstruction.trim();
            focusKeywords = immutableDistinct(focusKeywords);
        }
    }

    private static int clamp(int value, int defaultValue) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.clamp(value, 0, 100);
    }

    private static List<String> immutableDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }
}
