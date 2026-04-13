package com.schemaplexai.service.quality.orchestrator;

import com.schemaplexai.service.quality.detector.DetectorRegistry;
import com.schemaplexai.service.quality.detector.QualityDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 质量编排器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityOrchestrator {

    private final DetectorRegistry detectorRegistry;
    private final RabbitTemplate rabbitTemplate;

    public String triggerDetection(String specId, String agentExecutionId, String dimensionCode) {
        String taskId = UUID.randomUUID().toString();

        log.info("触发质量检测: taskId={}, specId={}, dimensionCode={}",
                taskId, specId, dimensionCode);

        Map<String, Object> message = new HashMap<>();
        message.put("taskId", taskId);
        message.put("specId", specId);
        message.put("agentExecutionId", agentExecutionId);
        message.put("dimensionCode", dimensionCode);
        message.put("type", "deviation_detect");

        rabbitTemplate.convertAndSend("sf.quality.check", message);

        return taskId;
    }

    public QualityDetector.DetectionResult executeDetection(
            String specId,
            String dimensionCode,
            String targetContent) {
        return executeDetection(specId, dimensionCode, targetContent, Map.of());
    }

    public QualityDetector.DetectionResult executeDetection(
            String specId,
            String dimensionCode,
            String targetContent,
            Map<String, Object> ruleConfig) {

        QualityDetector detector = detectorRegistry.getDetector(dimensionCode);
        if (detector == null) {
            log.warn("未找到检测器: dimensionCode={}", dimensionCode);
            return new QualityDetector.DetectionResult(false, "info", "无可用检测器", Map.of());
        }

        QualityDetector.DetectionContext context = new QualityDetector.DetectionContext(
                specId, null, dimensionCode, null, ruleConfig == null ? Map.of() : ruleConfig, targetContent
        );

        return detector.detect(context);
    }
}
