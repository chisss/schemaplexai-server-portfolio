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

        String targetContent = context.targetContent();
        if (targetContent == null || targetContent.isBlank()) {
            return new DetectionResult(
                    true,
                    DeviationSeverityEnum.WARNING.getCode(),
                    "目标内容为空",
                    Map.of("detector", "structural", "issueType", "empty_output")
            );
        }

        String normalized = targetContent.toLowerCase();
        boolean hasPlaceholder = normalized.contains("todo")
                || normalized.contains("tbd")
                || normalized.contains("待补充")
                || normalized.contains("待确认")
                || normalized.contains("placeholder");
        boolean tooShort = targetContent.trim().length() < 48;
        boolean missingStructure = !normalized.contains("##") && !normalized.contains("- ") && !normalized.contains("1.");

        if (hasPlaceholder || tooShort || missingStructure) {
            return new DetectionResult(
                    true,
                    DeviationSeverityEnum.WARNING.getCode(),
                    "输出结构不完整，包含占位符或信息密度不足",
                    Map.of(
                            "detector", "structural",
                            "issueType", hasPlaceholder ? "placeholder" : tooShort ? "too_short" : "missing_structure",
                            "contentLength", targetContent.trim().length()
                    )
            );
        }

        return new DetectionResult(
                false,
                DeviationSeverityEnum.INFO.getCode(),
                "检测通过",
                Map.of("detector", "structural", "contentLength", targetContent.trim().length())
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
