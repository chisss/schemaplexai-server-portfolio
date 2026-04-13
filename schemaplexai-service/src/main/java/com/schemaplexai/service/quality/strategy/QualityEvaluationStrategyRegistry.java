package com.schemaplexai.service.quality.strategy;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 质量评估策略注册表
 */
@Component
@RequiredArgsConstructor
public class QualityEvaluationStrategyRegistry {

    private final List<QualityEvaluationStrategy> strategies;

    public QualityEvaluationStrategy getStrategy(String issueType) {
        return strategies.stream()
                .filter(s -> s.supportedIssueType().equals(issueType))
                .findFirst()
                .orElse(null);
    }
}
