package com.schemaplexai.service.quality.detector.impl;

import com.schemaplexai.service.quality.detector.QualityDetector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认检测器实现
 */
@Slf4j
@Component
public class DefaultDetector implements QualityDetector {

    @Override
    public DetectionResult detect(DetectionContext context) {
        log.info("执行默认检测: dimensionCode={}, specId={}",
                context.dimensionCode(), context.specId());

        return new DetectionResult(
                false,
                "info",
                "默认检测器执行完成",
                Map.of("detector", "default")
        );
    }

    @Override
    public String getType() {
        return "default";
    }

    @Override
    public boolean supports(String dimensionCode) {
        return true;
    }
}
