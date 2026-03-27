package com.schemaplexai.service.quality.consumer;

import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 质量检测 MQ 消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QualityCheckConsumer {

    private final QualityOrchestrator qualityOrchestrator;

    @RabbitListener(queues = "sf.quality.check")
    public void handleQualityCheck(Map<String, Object> message) {
        if (message == null) {
            return;
        }

        String type = (String) message.get("type");
        String taskId = (String) message.get("taskId");
        String specId = (String) message.get("specId");

        log.info("收到质量检测消息: type={}, taskId={}, specId={}", type, taskId, specId);

        try {
            switch (type) {
                case "deviation_detect" -> handleDeviationDetect(message);
                case "intent_defect_analyze" -> handleIntentDefectAnalyze(message);
                case "cross_review" -> handleCrossReview(message);
                default -> log.warn("未知的质量检测类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理质量检测消息失败: type={}, error={}", type, e.getMessage(), e);
        }
    }

    private void handleDeviationDetect(Map<String, Object> message) {
        String specId = (String) message.get("specId");
        String agentExecutionId = (String) message.get("agentExecutionId");

        log.info("执行偏离检测: specId={}, agentExecutionId={}", specId, agentExecutionId);
        qualityOrchestrator.executeDetection(specId, "structural", "");
    }

    private void handleIntentDefectAnalyze(Map<String, Object> message) {
        String specId = (String) message.get("specId");
        String docType = (String) message.get("docType");

        log.info("执行意图缺陷分析: specId={}, docType={}", specId, docType);
        qualityOrchestrator.executeDetection(specId, "intent", "");
    }

    private void handleCrossReview(Map<String, Object> message) {
        String reviewId = (String) message.get("reviewId");
        String specId = (String) message.get("specId");

        log.info("执行交叉审查: reviewId={}, specId={}", reviewId, specId);
        qualityOrchestrator.executeDetection(specId, "cross_review", "");
    }
}
