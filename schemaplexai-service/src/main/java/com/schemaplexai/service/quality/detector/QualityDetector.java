package com.schemaplexai.service.quality.detector;

import java.util.Map;

/**
 * 质量检测器接口
 */
public interface QualityDetector {

    /**
     * 检测器类型
     */
    String getType();

    /**
     * 是否支持给定维度
     */
    default boolean supports(String dimensionCode) {
        return true;
    }

    /**
     * 执行检测
     */
    DetectionResult detect(DetectionContext context);

    /**
     * 检测上下文
     */
    record DetectionContext(
            String specId,
            String taskId,
            String dimensionCode,
            String ruleCode,
            Map<String, Object> ruleConfig,
            String targetContent
    ) {}

    /**
     * 检测结果
     */
    record DetectionResult(
            boolean hasIssue,
            String severity,
            String message,
            Map<String, Object> details
    ) {}
}
