package com.schemaplexai.service.quality.strategy.impl;

import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 偏离检测策略 — 使用模型评估 Agent 节点输出是否偏离预期
 */
@Component
@RequiredArgsConstructor
public class DeviationEvaluationStrategy implements QualityEvaluationStrategy {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的 AI 工作流质量审查员。你的任务是评估 Agent 节点的输出内容是否存在质量偏离问题。
            
            请从以下维度进行评估：
            1. 结构完整性：输出是否有清晰的结构，是否存在占位符（TODO/TBD/待补充等）
            2. 内容充实度：输出是否有实质内容，是否过于简短或空洞
            3. 语义清晰度：表达是否清晰，是否存在模糊、矛盾或不可执行的描述
            4. 任务符合度：输出是否符合节点任务的预期目标
            
            请严格按照以下 JSON 格式返回评估结果，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结评估结论",
              "findings": [
                {
                  "ruleCode": "命中的规则编码，若没有则返回 qa.dimension.<type>",
                  "type": "structural|semantic|omission|vagueness|placeholder",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题详细描述",
                  "location": "问题所在位置或上下文片段",
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
        return QualityIssueTypeEnum.DEVIATION.getCode();
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
        String userPrompt = buildUserPrompt(context);
        EvaluationResult rawResult = evaluator.evaluate(context.modelId(), systemPrompt.toString(), userPrompt);
        return reviewPolicyService.applyPolicy(rawResult, context.reviewPolicy());
    }

    private String buildUserPrompt(EvaluationContext context) {
        StringBuilder builder = new StringBuilder("""
                请评估以下 Agent 节点输出是否偏离参考规范、节点目标与质量规则。
                
                """);
        if (StringUtils.hasText(context.referenceContent())) {
            builder.append("【参考规范 / Spec 上下文】\n")
                    .append(context.referenceContent().trim())
                    .append("\n\n");
        }
        builder.append(String.format("""
                        节点ID：%s
                        节点名称：%s
                        
                        【输出内容】
                        %s
                        """,
                context.nodeId() != null ? context.nodeId() : "未知",
                context.nodeLabel() != null ? context.nodeLabel() : "未知",
                context.targetContent() != null ? context.targetContent() : "（空）"
        ));
        return builder.toString();
    }
}
