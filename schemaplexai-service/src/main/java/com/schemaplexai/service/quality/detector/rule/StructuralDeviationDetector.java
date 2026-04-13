package com.schemaplexai.service.quality.detector.rule;

import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 结构偏离检测器 — 使用模型评估输出结构质量
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StructuralDeviationDetector implements QualityDetector {

    private static final String SYSTEM_PROMPT = """
            你是一个专业的结构质量检测员，负责评估 AI 输出内容的结构完整性。
            请检查内容是否存在：占位符（TODO/TBD/待补充等）、结构缺失、内容过于简短、格式混乱等问题。

            请严格按照以下 JSON 格式返回，不要添加任何额外说明：
            {
              "hasIssue": true/false,
              "score": 0-100,
              "summary": "一句话总结",
              "findings": [
                {
                  "ruleCode": "规则编码或 qa.dimension.structural",
                  "type": "structural|placeholder|too_short|missing_structure",
                  "severity": "critical|warning|info",
                  "confidence": 0-100,
                  "title": "问题标题",
                  "description": "问题描述",
                  "location": "问题位置",
                  "suggestion": "改进建议"
                }
              ]
            }
            """;

    private final ModelBasedQualityEvaluator modelEvaluator;

    @Override
    public DetectionResult detect(DetectionContext context) {
        log.info("执行结构偏离检测: specId={}, dimensionCode={}", context.specId(), context.dimensionCode());

        String targetContent = context.targetContent();
        if (!StringUtils.hasText(targetContent)) {
            return new DetectionResult(true, DeviationSeverityEnum.WARNING.getCode(), "目标内容为空",
                    Map.of("detector", "structural", "issueType", "empty_output"));
        }

        // 从 ruleConfig 中获取模型 ID
        String modelId = context.ruleConfig() != null
                ? String.valueOf(context.ruleConfig().getOrDefault("modelId", ""))
                : "";

        if (!StringUtils.hasText(modelId)) {
            log.warn("结构检测器未配置模型，跳过模型评估: specId={}", context.specId());
            return new DetectionResult(false, DeviationSeverityEnum.INFO.getCode(), "未配置评估模型，跳过检测",
                    Map.of("detector", "structural"));
        }

        String userPrompt = "请评估以下内容的结构质量：\n\n" + targetContent;
        QualityEvaluationStrategy.EvaluationResult result =
                modelEvaluator.evaluate(modelId, SYSTEM_PROMPT, userPrompt);

        String severity = result.findings().stream()
                .map(QualityEvaluationStrategy.Finding::severity)
                .filter(s -> "critical".equals(s) || "warning".equals(s))
                .findFirst()
                .orElse(DeviationSeverityEnum.INFO.getCode());

        return new DetectionResult(result.hasIssue(), severity, result.summary(),
                Map.of("detector", "structural", "score", result.score(),
                        "findingCount", result.findings().size()));
    }

    @Override
    public String getType() {
        return "model";
    }

    @Override
    public boolean supports(String dimensionCode) {
        return "structural".equals(dimensionCode);
    }
}
