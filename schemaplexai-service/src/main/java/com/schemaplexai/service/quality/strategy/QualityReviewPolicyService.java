package com.schemaplexai.service.quality.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.QualityRuleMapper;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质量评估运行时策略解析与结果裁剪服务
 */
@Service
@RequiredArgsConstructor
public class QualityReviewPolicyService {

    private static final String STATUS_ACTIVE = "active";

    private final QualityRuleMapper qualityRuleMapper;

    public QualityReviewPolicy resolvePolicy(QualityProfile profile, String issueType, String triggerMode) {
        if (profile == null) {
            return QualityReviewPolicy.empty(issueType, triggerMode);
        }
        Map<String, QualityReviewPolicy.DimensionConfig> dimensionConfigs = parseDimensionConfigs(profile.getThresholdConfig());
        List<String> enabledDimensions = resolveEnabledDimensions(profile, issueType, dimensionConfigs.keySet());
        if (enabledDimensions.isEmpty()) {
            enabledDimensions = defaultDimensions(issueType);
        }
        enabledDimensions.forEach(dimension ->
                dimensionConfigs.putIfAbsent(dimension, QualityReviewPolicy.DimensionConfig.defaultConfig()));
        List<QualityRule> rules = loadRules(profile, triggerMode, enabledDimensions);
        List<QualityReviewPolicy.RuleDefinition> ruleDefinitions = rules.stream()
                .map(this::toRuleDefinition)
                .toList();
        return new QualityReviewPolicy(issueType, triggerMode, enabledDimensions, dimensionConfigs, ruleDefinitions);
    }

    public String renderPromptBlock(QualityReviewPolicy policy) {
        if (policy == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("请严格使用以下质量策略执行评估：\n");
        builder.append("- 仅输出高置信度、可复核、能落地的问题，禁止为了凑数输出泛泛而谈的观察。\n");
        builder.append("- 每条 finding 必须返回 ruleCode、type、severity、confidence、title、description、location、suggestion。\n");
        if (!policy.enabledDimensions().isEmpty()) {
            builder.append("- 当前启用维度：").append(String.join("、", policy.enabledDimensions())).append("\n");
        }
        if (!policy.dimensionConfigs().isEmpty()) {
            builder.append("维度阈值：\n");
            for (String dimension : policy.enabledDimensions()) {
                QualityReviewPolicy.DimensionConfig config = policy.dimensionConfig(dimension);
                builder.append("- ")
                        .append(dimension)
                        .append("：strictness=").append(config.strictness())
                        .append("，confidence>=").append(config.confidenceThreshold())
                        .append("，maxFindings=").append(config.maxFindings());
                if (!CollectionUtils.isEmpty(config.focusKeywords())) {
                    builder.append("，focusKeywords=").append(String.join("、", config.focusKeywords()));
                }
                if (StringUtils.hasText(config.evaluationPrompt())) {
                    builder.append("，补充要求=").append(config.evaluationPrompt());
                }
                builder.append("\n");
            }
        }
        if (!policy.rules().isEmpty()) {
            builder.append("启用规则：\n");
            int index = 1;
            for (QualityReviewPolicy.RuleDefinition rule : policy.rules()) {
                builder.append(index++).append(". [").append(rule.code()).append("] ")
                        .append(StringUtils.hasText(rule.name()) ? rule.name() : "未命名规则");
                if (StringUtils.hasText(rule.dimensionCode())) {
                    builder.append(" | dimension=").append(rule.dimensionCode());
                }
                if (StringUtils.hasText(rule.severity())) {
                    builder.append(" | severity=").append(rule.severity());
                }
                if (rule.weight() != null) {
                    builder.append(" | weight=").append(rule.weight());
                }
                builder.append("\n");
                if (StringUtils.hasText(rule.promptInstruction())) {
                    builder.append("   要求：").append(rule.promptInstruction()).append("\n");
                }
                if (!CollectionUtils.isEmpty(rule.focusKeywords())) {
                    builder.append("   关键词：").append(String.join("、", rule.focusKeywords())).append("\n");
                }
            }
        }
        return builder.toString().trim();
    }

    public QualityEvaluationStrategy.EvaluationResult applyPolicy(
            QualityEvaluationStrategy.EvaluationResult rawResult,
            QualityReviewPolicy policy) {
        if (rawResult == null) {
            return QualityEvaluationStrategy.EvaluationResult.empty();
        }
        if (policy == null) {
            policy = QualityReviewPolicy.empty(null, null);
        }
        Map<String, Integer> ruleWeightMap = policy.rules().stream()
                .collect(Collectors.toMap(QualityReviewPolicy.RuleDefinition::code,
                        rule -> rule.weight() == null ? 100 : rule.weight(),
                        (left, right) -> left, LinkedHashMap::new));

        List<QualityEvaluationStrategy.Finding> normalized = rawResult.findings().stream()
                .map(this::normalizeFinding)
                .sorted(Comparator
                        .comparingInt((QualityEvaluationStrategy.Finding finding) -> severityRank(finding.severity())).reversed()
                        .thenComparingInt(QualityEvaluationStrategy.Finding::confidence).reversed()
                        .thenComparingInt(finding -> ruleWeightMap.getOrDefault(finding.ruleCode(), 100)).reversed())
                .toList();

        List<QualityEvaluationStrategy.Finding> filtered = new ArrayList<>();
        Map<String, Integer> dimensionCounters = new LinkedHashMap<>();
        int totalLimit = policy.overallMaxFindings();
        for (QualityEvaluationStrategy.Finding finding : normalized) {
            String dimension = normalizeDimension(finding.type());
            if (!policy.isDimensionEnabled(dimension)) {
                continue;
            }
            if (finding.confidence() < policy.confidenceThresholdFor(dimension)) {
                continue;
            }
            int currentCount = dimensionCounters.getOrDefault(dimension, 0);
            if (currentCount >= policy.maxFindingsFor(dimension)) {
                continue;
            }
            filtered.add(finding);
            dimensionCounters.put(dimension, currentCount + 1);
            if (filtered.size() >= totalLimit) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            return buildFilteredFallbackResult(rawResult, policy, normalized);
        }

        String summary = StringUtils.hasText(rawResult.summary())
                ? rawResult.summary()
                : "检测完成，发现 " + filtered.size() + " 项需要关注的问题";
        return new QualityEvaluationStrategy.EvaluationResult(true, rawResult.score(), summary, filtered);
    }

    private QualityEvaluationStrategy.EvaluationResult buildFilteredFallbackResult(
            QualityEvaluationStrategy.EvaluationResult rawResult,
            QualityReviewPolicy policy,
            List<QualityEvaluationStrategy.Finding> normalizedFindings) {
        String summary = StringUtils.hasText(rawResult.summary())
                ? rawResult.summary()
                : "未发现达到质量阈值的问题";
        if (normalizedFindings.isEmpty()) {
            return new QualityEvaluationStrategy.EvaluationResult(
                    rawResult.hasIssue(),
                    rawResult.score(),
                    summary,
                    List.of()
            );
        }

        QualityEvaluationStrategy.Finding strongestFinding = normalizedFindings.getFirst();
        String dimension = normalizeDimension(strongestFinding.type());
        int confidenceThreshold = policy.confidenceThresholdFor(dimension);
        int scoreCeiling = scoreCeilingForSeverity(strongestFinding.severity());
        String fallbackSummary = "模型发现潜在质量风险，但原始结果未通过当前策略阈值，已保留人工复核信号";
        String fallbackDescription = fallbackText(strongestFinding.description(), "模型输出了潜在风险")
                + "；原始置信度=" + strongestFinding.confidence()
                + "，当前阈值=" + confidenceThreshold
                + "，建议结合规则配置人工复核。";

        QualityEvaluationStrategy.Finding fallbackFinding = new QualityEvaluationStrategy.Finding(
                dimension,
                strongestFinding.severity(),
                Math.max(strongestFinding.confidence(), 60),
                strongestFinding.ruleCode(),
                fallbackText(strongestFinding.title(), "存在潜在质量风险"),
                fallbackDescription,
                fallbackText(strongestFinding.location(), ""),
                fallbackText(strongestFinding.suggestion(), "根据质量规则补齐缺失内容并重新审查")
        );

        return new QualityEvaluationStrategy.EvaluationResult(
                true,
                Math.min(rawResult.score(), scoreCeiling),
                fallbackSummary,
                List.of(fallbackFinding)
        );
    }

    public QualityEvaluationStrategy.Finding normalizeFinding(QualityEvaluationStrategy.Finding finding) {
        if (finding == null) {
            return new QualityEvaluationStrategy.Finding(
                    "general", "warning", 80, "qa.dimension.general",
                    "未命名问题", "", "", "");
        }
        String dimension = normalizeDimension(finding.type());
        String severity = StringUtils.hasText(finding.severity()) ? finding.severity().trim().toLowerCase(Locale.ROOT) : "warning";
        int confidence = finding.confidence() > 0 ? Math.min(finding.confidence(), 100) : 80;
        String ruleCode = StringUtils.hasText(finding.ruleCode())
                ? finding.ruleCode().trim()
                : "qa.dimension." + dimension;
        return new QualityEvaluationStrategy.Finding(
                dimension,
                severity,
                confidence,
                ruleCode,
                fallbackText(finding.title(), "未命名问题"),
                fallbackText(finding.description(), ""),
                fallbackText(finding.location(), ""),
                fallbackText(finding.suggestion(), "")
        );
    }

    public String normalizeDimension(String type) {
        if (!StringUtils.hasText(type)) {
            return "general";
        }
        return switch (type.trim().toLowerCase(Locale.ROOT)) {
            case "placeholder", "too_short", "missing_structure", "structural" -> "structural";
            case "missing_acceptance", "omission" -> "omission";
            case "ambiguity" -> "ambiguity";
            case "contradiction" -> "contradiction";
            case "vagueness" -> "vagueness";
            case "semantic" -> "semantic";
            default -> type.trim().toLowerCase(Locale.ROOT);
        };
    }

    private Map<String, QualityReviewPolicy.DimensionConfig> parseDimensionConfigs(Map<String, Object> thresholdConfig) {
        if (thresholdConfig == null || thresholdConfig.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, QualityReviewPolicy.DimensionConfig> result = new LinkedHashMap<>();
        thresholdConfig.forEach((dimension, value) -> {
            if (!StringUtils.hasText(dimension) || !(value instanceof Map<?, ?> rawMap)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) rawMap;
            result.put(dimension.trim(), new QualityReviewPolicy.DimensionConfig(
                    boolValue(map.get("enabled"), true),
                    intValue(map.get("strictness"), QualityReviewPolicy.DEFAULT_STRICTNESS),
                    intValue(map.get("confidenceThreshold"), QualityReviewPolicy.DEFAULT_CONFIDENCE_THRESHOLD),
                    intValue(map.get("maxFindings"), QualityReviewPolicy.DEFAULT_MAX_FINDINGS),
                    stringList(map.get("focusKeywords")),
                    boolValue(map.get("blockOnCritical"), true),
                    stringValue(map.get("evaluationPrompt"))
            ));
        });
        return result;
    }

    private List<String> resolveEnabledDimensions(QualityProfile profile, String issueType, Set<String> configuredDimensions) {
        LinkedHashSet<String> dimensions = new LinkedHashSet<>(defaultDimensions(issueType));
        if (profile.getEnabledDimensionCodes() != null && !profile.getEnabledDimensionCodes().isEmpty()) {
            profile.getEnabledDimensionCodes().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(dimensions::add);
        } else {
            configuredDimensions.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(dimensions::add);
        }
        return List.copyOf(dimensions);
    }

    private List<String> defaultDimensions(String issueType) {
        if ("intent_defect".equalsIgnoreCase(issueType)) {
            return List.of("ambiguity", "contradiction", "omission", "vagueness");
        }
        if ("deviation".equalsIgnoreCase(issueType)) {
            return List.of("structural", "semantic", "ambiguity", "contradiction", "omission", "vagueness");
        }
        return List.of("structural", "semantic", "ambiguity", "contradiction", "omission", "vagueness");
    }

    private List<QualityRule> loadRules(QualityProfile profile, String triggerMode, List<String> enabledDimensions) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getStatus, STATUS_ACTIVE)
                .orderByDesc(QualityRule::getWeight)
                .orderByAsc(QualityRule::getCreatedAt);
        if (StringUtils.hasText(profile.getTenantId())) {
            wrapper.eq(QualityRule::getTenantId, profile.getTenantId());
        }
        List<String> enabledRuleCodes = profile.getEnabledRuleCodes() == null ? List.of() : profile.getEnabledRuleCodes().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (!enabledRuleCodes.isEmpty()) {
            wrapper.in(QualityRule::getCode, enabledRuleCodes);
        } else if (!enabledDimensions.isEmpty()) {
            wrapper.in(QualityRule::getDimensionCode, enabledDimensions);
        } else {
            return List.of();
        }
        if (StringUtils.hasText(triggerMode)) {
            wrapper.and(query -> query.eq(QualityRule::getTriggerMode, triggerMode)
                    .or()
                    .isNull(QualityRule::getTriggerMode)
                    .or()
                    .eq(QualityRule::getTriggerMode, ""));
        }
        return qualityRuleMapper.selectList(wrapper);
    }

    private QualityReviewPolicy.RuleDefinition toRuleDefinition(QualityRule rule) {
        Map<String, Object> ruleConfig = rule.getRuleConfig() == null ? Map.of() : rule.getRuleConfig();
        return new QualityReviewPolicy.RuleDefinition(
                rule.getCode(),
                rule.getName(),
                rule.getDimensionCode(),
                rule.getSeverity(),
                rule.getWeight(),
                rule.getConditionExpr(),
                buildRuleInstruction(rule, ruleConfig),
                mergeKeywords(
                        stringList(ruleConfig.get("focusKeywords")),
                        stringList(ruleConfig.get("keywords"))
                )
        );
    }

    private String buildRuleInstruction(QualityRule rule, Map<String, Object> ruleConfig) {
        List<String> parts = new ArrayList<>();
        addTextIfPresent(parts, stringValue(ruleConfig.get("evaluationPrompt")));
        addTextIfPresent(parts, stringValue(ruleConfig.get("prompt")));
        addTextIfPresent(parts, stringValue(ruleConfig.get("instruction")));
        addTextIfPresent(parts, stringValue(ruleConfig.get("description")));
        addCollection(parts, "mustInclude", ruleConfig.get("mustInclude"));
        addCollection(parts, "mustNotInclude", ruleConfig.get("mustNotInclude"));
        addCollection(parts, "checkpoints", ruleConfig.get("checkpoints"));
        addCollection(parts, "examples", ruleConfig.get("examples"));
        if (StringUtils.hasText(rule.getConditionExpr())) {
            parts.add("判定条件：" + rule.getConditionExpr().trim());
        }
        if (parts.isEmpty()) {
            parts.add(StringUtils.hasText(rule.getName()) ? rule.getName() : "遵守该质量规则");
        }
        return String.join("；", parts);
    }

    private void addCollection(List<String> parts, String label, Object value) {
        List<String> items = stringList(value);
        if (!items.isEmpty()) {
            parts.add(label + "=" + String.join("、", items));
        }
    }

    private void addTextIfPresent(List<String> parts, String value) {
        if (StringUtils.hasText(value)) {
            parts.add(value.trim());
        }
    }

    private int severityRank(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 3;
            case "warning" -> 2;
            case "info" -> 1;
            default -> 0;
        };
    }

    private int scoreCeilingForSeverity(String severity) {
        return switch (severity == null ? "" : severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 60;
            case "warning" -> 75;
            case "info" -> 85;
            default -> 80;
        };
    }

    private boolean boolValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value).trim();
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .stream()
                    .toList();
        }
        String text = String.valueOf(value).trim();
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return List.of(text);
    }

    private List<String> mergeKeywords(List<String> left, List<String> right) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (left != null) {
            keywords.addAll(left);
        }
        if (right != null) {
            keywords.addAll(right);
        }
        return List.copyOf(keywords);
    }

    private String fallbackText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }
}
