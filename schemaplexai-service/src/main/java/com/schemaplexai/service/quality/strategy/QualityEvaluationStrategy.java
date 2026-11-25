package com.schemaplexai.service.quality.strategy;

import java.util.List;
import java.util.Map;

/**
 * 质量评估策略接口（策略模式）
 * 所有质量检测均通过模型能力实现，而非硬编码字符串匹配
 */
public interface QualityEvaluationStrategy {

    /**
     * 策略支持的 issueType
     */
    String supportedIssueType();

    /**
     * 执行评估，返回结构化结果
     */
    EvaluationResult evaluate(EvaluationContext context);

    record EvaluationContext(
            String specId,
            String issueType,
            String docType,
            String nodeId,
            String nodeLabel,
            String targetContent,
            String referenceContent,
            String modelId,
            String extraPrompt,
            Map<String, Object> extraParams,
            QualityReviewPolicy reviewPolicy
    ) {}

    record Finding(
            String type,
            String severity,
            int confidence,
            String ruleCode,
            String title,
            String description,
            String location,
            String suggestion
    ) {}

    record EvaluationResult(
            boolean hasIssue,
            int score,
            String summary,
            List<Finding> findings,
            ModelAssessment modelAssessment
    ) {
        public EvaluationResult(boolean hasIssue, int score, String summary, List<Finding> findings) {
            this(hasIssue, score, summary, findings, null);
        }

        public EvaluationResult withModelAssessment(String status, String reason) {
            return new EvaluationResult(hasIssue, score, summary, findings, new ModelAssessment(status, reason));
        }

        public static EvaluationResult empty() {
            return new EvaluationResult(false, 100, "未发现问题", List.of());
        }
    }

    record ModelAssessment(String status, String reason) {}
}
