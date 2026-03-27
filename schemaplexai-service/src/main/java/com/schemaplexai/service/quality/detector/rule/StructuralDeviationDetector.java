package com.schemaplexai.service.quality.detector.rule;

import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.service.quality.detector.QualityDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 结构偏离检测器（示例实现）
 */
@Slf4j
@Component
public class StructuralDeviationDetector implements QualityDetector {

    @Override
    public DetectionResult detect(DetectionContext context) {
        log.info("执行结构偏离检测: specId={}, dimensionCode={}",
                context.specId(), context.dimensionCode());

        // 简单示例：检测是否为空
        boolean hasIssue = context.targetContent() == null || context.targetContent().isEmpty();

        return new DetectionResult(
                hasIssue,
                hasIssue ? DeviationSeverityEnum.WARNING.getCode() : DeviationSeverityEnum.INFO.getCode(),
                hasIssue ? "目标内容为空" : "检测通过",
                Map.of("detector", "structural")
        );
    }

    @Override
    public String getType() {
        return "rule";
    }

    @Override
    public boolean supports(String dimensionCode) {
        return "structural".equals(dimensionCode);
    }
}
