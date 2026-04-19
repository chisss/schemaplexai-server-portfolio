package com.schemaplexai.service.quality.gate;

import com.schemaplexai.common.enums.GateDecisionEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量闸门评估器
 * 从 BuiltinQualityAssuranceService.evaluateWorkflowNodeGate() 提取并增强，
 * 新增 retry 决策支持。
 */
@Slf4j
@Component
public class QualityGateEvaluator {

    private static final String GATE_MODE_DISABLED = "disabled";
    private static final String GATE_MODE_WARN = GateDecisionEnum.WARN.getCode();
    private static final String GATE_MODE_PAUSE = GateDecisionEnum.PAUSE.getCode();
    private static final String GATE_MODE_FAIL = GateDecisionEnum.FAIL.getCode();

    /** retry 触发区间：score 在 [minScore - RETRY_SCORE_MARGIN, minScore) 之间 */
    private static final int RETRY_SCORE_MARGIN = 10;
    /** 缺省兜底质量分阈值，避免未配置节点把明显不合格产物直接放行。 */
    private static final int DEFAULT_MIN_QUALITY_SCORE = 60;

    /**
     * 评估质量闸门决策
     */
    public QualityGateDecision evaluate(QualityGateContext ctx) {
        String gateMode = resolveGateMode(ctx);
        boolean hasExplicitGateMode = hasExplicitGateMode(ctx);
        boolean hasExplicitThresholdConfig = hasExplicitThresholdConfig(ctx);
        boolean useDefaultThresholds = !hasExplicitThresholdConfig;
        if (GATE_MODE_DISABLED.equalsIgnoreCase(gateMode)) {
            return QualityGateDecision.pass("质量闸门已禁用");
        }
        if (ctx.qualityResult().isEmpty()) {
            return QualityGateDecision.pass("无质量检查结果");
        }

        boolean taskFailed = "failed".equalsIgnoreCase(asText(ctx.qualityResult().get("qualityTaskStatus")));
        Integer score = readInteger(ctx.qualityResult().get("qualityScore"));
        int deviationCount = readIntOrDefault(ctx.qualityResult().get("qualityDeviationCount"), 0);
        int warningCount = readIntOrDefault(ctx.qualityResult().get("qualityWarningCount"), deviationCount);

        Integer minQualityScore = readInteger(ctx.nodeConfig().get("minQualityScore"));
        if (minQualityScore == null && useDefaultThresholds) {
            minQualityScore = DEFAULT_MIN_QUALITY_SCORE;
        }
        Integer maxDeviationCount = readInteger(ctx.nodeConfig().get("maxDeviationCount"));
        Integer maxWarningCount = readInteger(ctx.nodeConfig().get("maxWarningCount"));
        Boolean blockOnTaskFailedConfig = readBoolean(ctx.nodeConfig().get("blockOnQualityTaskFailed"));
        boolean blockOnTaskFailed = blockOnTaskFailedConfig != null ? blockOnTaskFailedConfig : useDefaultThresholds;

        List<String> reasons = new ArrayList<>();
        boolean scoreBelow = false;

        if (taskFailed && blockOnTaskFailed) {
            reasons.add("质量任务执行失败");
        }
        if (minQualityScore != null && score != null && score < minQualityScore) {
            reasons.add("质量分低于阈值(" + score + "<" + minQualityScore + ")");
            scoreBelow = true;
        }
        if (maxDeviationCount != null && deviationCount > maxDeviationCount) {
            reasons.add("偏离数量超限(" + deviationCount + ">" + maxDeviationCount + ")");
        }
        if (maxWarningCount != null && warningCount > maxWarningCount) {
            reasons.add("预警数量超限(" + warningCount + ">" + maxWarningCount + ")");
        }

        if (reasons.isEmpty()) {
            return QualityGateDecision.pass("质量闸门通过");
        }

        String summary = String.join("；", reasons);

        // retry 判定：仅在 score 接近阈值、且还有重试次数时触发
        if (scoreBelow && canRetry(ctx, score, minQualityScore)) {
            String retryPrompt = buildRetryPrompt(ctx.qualityResult());
            log.info("质量闸门触发 retry: score={}, minScore={}, retryCount={}/{}",
                    score, minQualityScore, ctx.retryCount(), ctx.maxRetryCount());
            return QualityGateDecision.retry("质量分接近阈值，自动重试优化: " + summary, retryPrompt);
        }

        if (GATE_MODE_FAIL.equalsIgnoreCase(gateMode)) {
            return QualityGateDecision.fail(summary);
        }
        if (GATE_MODE_PAUSE.equalsIgnoreCase(gateMode)) {
            return QualityGateDecision.pause(summary);
        }
        if (useDefaultThresholds && !hasExplicitGateMode) {
            return QualityGateDecision.pause("命中默认质量闸门: " + summary);
        }
        return QualityGateDecision.warn(summary);
    }

    /**
     * 解析闸门模式：节点配置 > Profile 配置 > 默认 warn
     */
    private String resolveGateMode(QualityGateContext ctx) {
        String nodeGateMode = asText(ctx.nodeConfig().get("qualityGateMode"));
        if (StringUtils.hasText(nodeGateMode)) {
            return nodeGateMode;
        }
        String profileGateMode = asText(ctx.profileConfig().get("qualityGateMode"));
        if (StringUtils.hasText(profileGateMode)) {
            return profileGateMode;
        }
        return GATE_MODE_WARN;
    }

    private boolean hasExplicitThresholdConfig(QualityGateContext ctx) {
        return ctx.nodeConfig().containsKey("minQualityScore")
                || ctx.nodeConfig().containsKey("maxDeviationCount")
                || ctx.nodeConfig().containsKey("maxWarningCount")
                || ctx.nodeConfig().containsKey("blockOnQualityTaskFailed");
    }

    private boolean hasExplicitGateMode(QualityGateContext ctx) {
        return StringUtils.hasText(asText(ctx.nodeConfig().get("qualityGateMode")))
                || StringUtils.hasText(asText(ctx.profileConfig().get("qualityGateMode")));
    }

    /**
     * 判断是否可以 retry
     */
    private boolean canRetry(QualityGateContext ctx, Integer score, Integer minQualityScore) {
        if (ctx.maxRetryCount() <= 0 || ctx.retryCount() >= ctx.maxRetryCount()) {
            return false;
        }
        if (score == null || minQualityScore == null) {
            return false;
        }
        // score 在 [minScore - margin, minScore) 区间才触发 retry
        return score >= (minQualityScore - RETRY_SCORE_MARGIN) && score < minQualityScore;
    }

    /**
     * 构建重试修正提示词
     */
    private String buildRetryPrompt(Map<String, Object> qualityResult) {
        String summary = asText(qualityResult.get("qualitySummary"));
        if (!StringUtils.hasText(summary)) {
            return "请根据质量检查反馈优化输出，提高质量分数。";
        }
        return "质量检查发现以下问题，请针对性优化：\n" + summary +
                "\n\n请修正上述问题后重新输出。";
    }

    // ==================== 工具方法 ====================

    private Integer readInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int readIntOrDefault(Object value, int defaultValue) {
        Integer result = readInteger(value);
        return result != null ? result : defaultValue;
    }

    private Boolean readBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return switch (normalized) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> null;
        };
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
