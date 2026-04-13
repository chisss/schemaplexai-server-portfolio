package com.schemaplexai.service.quality.strategy.impl;

import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 意图缺陷检测策略 — 使用模型评估文档是否存在意图缺陷
 */
@Component
@RequiredArgsConstructor
public class IntentDefectEvaluationStrategy implements QualityEvaluationStrategy {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的需求/设计文档质量审查员。你的任务是评估文档是否存在意图缺陷，确保文档能够清晰传达意图并支撑后续研发流程。
            
            请从以下维度进行评估：
            1. 完整性：文档是否包含必要章节（背景、目标、范围、约束、验收标准等）
            2. 清晰度：需求/设计意图是否清晰，是否存在歧义或模糊表达
            3. 可执行性：描述是否足够具体，研发人员能否据此直接执行
            4. 一致性：文档内部是否存在矛盾或冲突
            5. 可验证性：是否有明确的验收标准或成功指标
            
            请严格按照以下 JSON 格式返回评估结果，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结评估结论",
              "findings": [
                {
                  "ruleCode": "命中的规则编码，若没有则返回 qa.dimension.<type>",
                  "type": "omission|vagueness|ambiguity|contradiction|missing_acceptance",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题详细描述",
                  "location": "问题所在章节或内容片段",
                  "suggestion": "改进建议"
                }
              ]
            }
            
            若无问题，findings 返回空数组 []，score 返回 90-100。
            """;

    private final ModelBasedQualityEvaluator evaluator;
    private final QualityReviewPolicyService reviewPolicyService;

    @Override
    public String supportedIssueType() {
        return "intent_defect";
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        StringBuilder systemPrompt = new StringBuilder(SYSTEM_PROMPT);
        if (context.reviewPolicy() != null) {
            systemPrompt.append("\n\n").append(reviewPolicyService.renderPromptBlock(context.reviewPolicy()));
        }
        if (StringUtils.hasText(context.extraPrompt())) {
            systemPrompt.append("\n\n补充评估要求：\n").append(context.extraPrompt().trim());
        }
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("请评估以下")
                .append(context.docType() != null ? context.docType() : "规格")
                .append("文档是否存在意图缺陷。\n\n");
        if (StringUtils.hasText(context.referenceContent())) {
            userPrompt.append("【补充上下文】\n").append(context.referenceContent().trim()).append("\n\n");
        }
        userPrompt.append("【文档内容】\n")
                .append(context.targetContent() != null ? context.targetContent() : "（空）");
        EvaluationResult rawResult = evaluator.evaluate(context.modelId(), systemPrompt.toString(), userPrompt.toString());
        return reviewPolicyService.applyPolicy(rawResult, context.reviewPolicy());
    }
}
