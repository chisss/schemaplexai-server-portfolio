package com.schemaplexai.service.quality.feedback;

import com.schemaplexai.common.enums.DeviationSeverityEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 质量反馈处理器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackProcessor {

    public void processFeedback(String taskId, String severity, Map<String, Object> result) {
        log.info("处理质量反馈: taskId=, severity={}", taskId, severity);

        if (DeviationSeverityEnum.CRITICAL.getCode().equalsIgnoreCase(severity)) {
            handleCriticalIssue(taskId, result);
        } else if (DeviationSeverityEnum.WARNING.getCode().equalsIgnoreCase(severity)) {
            handleWarning(taskId, result);
        } else {
            handleInfo(taskId, result);
        }
    }

    private void handleCriticalIssue(String taskId, Map<String, Object> result) {
        log.warn("发现严重问题，阻断流程: taskId={}", taskId);
    }

    private void handleWarning(String taskId, Map<String, Object> result) {
        log.info("发现警告问题，记录通知: taskId={}", taskId);
    }

    private void handleInfo(String taskId, Map<String, Object> result) {
        log.debug("信息级反馈: taskId={}", taskId);
    }
}
