package com.schemaplexai.service.evaluation.impl;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 评估指标计算器
 */
@Component
public class EvalMetricCalculator {

    /**
     * 计算简单文本相似评分，后续可替换为更严格的 LLM Judge。
     */
    public int calculateScore(String output, String expectedOutput) {
        if (!StringUtils.hasText(expectedOutput)) {
            return StringUtils.hasText(output) ? 80 : 0;
        }
        if (!StringUtils.hasText(output)) {
            return 0;
        }
        String normalizedOutput = normalize(output);
        String normalizedExpected = normalize(expectedOutput);
        if (normalizedOutput.contains(normalizedExpected) || normalizedExpected.contains(normalizedOutput)) {
            return 100;
        }
        int common = 0;
        for (String token : normalizedExpected.split("\\s+")) {
            if (StringUtils.hasText(token) && normalizedOutput.contains(token)) {
                common++;
            }
        }
        int total = Math.max(1, normalizedExpected.split("\\s+").length);
        return Math.min(95, Math.max(20, (int) Math.round(common * 100.0 / total)));
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }
}
