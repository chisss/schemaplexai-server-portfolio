package com.schemaplexai.service.quality.detector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检测器注册中心
 */
@Slf4j
@Component
public class DetectorRegistry {

    private final Map<String, QualityDetector> detectors = new ConcurrentHashMap<>();

    public DetectorRegistry(List<QualityDetector> detectorList) {
        detectorList.forEach(this::register);
    }

    public void register(QualityDetector detector) {
        if (detector == null || !StringUtils.hasText(detector.getType())) {
            return;
        }
        detectors.put(detector.getType(), detector);
        log.info("注册检测器: type={}", detector.getType());
    }

    public QualityDetector getDetector(String dimensionCode) {
        if (!StringUtils.hasText(dimensionCode)) {
            return null;
        }
        return detectors.values().stream()
                .filter(d -> d.supports(dimensionCode))
                .findFirst().orElse(null);
    }

    public Map<String, QualityDetector> allDetectors() {
        return Collections.unmodifiableMap(detectors);
    }
}
