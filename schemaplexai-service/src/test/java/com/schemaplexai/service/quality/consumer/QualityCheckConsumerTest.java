package com.schemaplexai.service.quality.consumer;

import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QualityCheckConsumerTest {

    @Test
    void shouldForwardOriginalTaskAndAcceptMessagesFromOlderPublishers() {
        var assurance = mock(BuiltinQualityAssuranceService.class);
        var consumer = new QualityCheckConsumer(assurance);
        Map<String, Object> message = new HashMap<>(Map.of(
                "type", "deviation_detect", "taskId", "task-1", "tenantId", "tenant-1",
                "agentExecutionId", "exec-1", "targetContent", "当前输出", "originalTask", "原始任务"));

        consumer.handleQualityCheck(message);
        verify(assurance).handleDeviationMessage("task-1", null, "exec-1", "当前输出", "原始任务");

        message.remove("originalTask");
        message.put("taskId", "task-2");
        consumer.handleQualityCheck(message);
        verify(assurance).handleDeviationMessage("task-2", null, "exec-1", "当前输出", null);
    }
}
