package com.schemaplexai.service.quality.task;

import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.QualityTaskMapper;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.QualityTask;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy.Finding;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QualityTaskManagerTest {

    private final QualityTaskMapper tasks = mock(QualityTaskMapper.class);
    private final QualityDeviationMapper deviations = mock(QualityDeviationMapper.class);
    private final QualityTaskManager manager = new QualityTaskManager(tasks, deviations);
    private final QualityCheckRequest request = new QualityCheckRequest(
            null, "待补充", null, "deviation", "manual", "async", null, Map.of(),
            null, "execution-1", null, null, null, "tenant-1", 0);
    private final Finding finding = new Finding("placeholder", "warning", 90,
            "qa.dimension.structural", "发现占位符", "输出没有实质内容", "第一段", "补充工作结果");

    @Test
    void shouldStoreManualFindingBeforeMarkingTaskSucceeded() {
        Map<String, Object> summary = Map.of("deviationCount", 1, "score", 15);
        manager.completeManualDeviationTask("task-1", request, List.of(finding), summary);

        var deviation = ArgumentCaptor.forClass(QualityDeviation.class);
        var task = ArgumentCaptor.forClass(QualityTask.class);
        var order = inOrder(deviations, tasks);
        order.verify(deviations).insert(deviation.capture());
        order.verify(tasks).updateById(task.capture());

        assertThat(deviation.getValue()).satisfies(row -> {
            assertThat(row.getTenantId()).isEqualTo("tenant-1");
            assertThat(row.getTaskId()).isEqualTo("task-1");
            assertThat(row.getAgentExecutionId()).isEqualTo("execution-1");
            assertThat(row.getSpecId()).isNull();
            assertThat(row.getSourceType()).isEqualTo("manual");
            assertThat(row.getStatus()).isEqualTo("open");
            assertThat(row.getDeviationType()).isEqualTo("structural");
            assertThat(row.getSeverity()).isEqualTo("warning");
            assertThat(row.getRuleCode()).isEqualTo("qa.dimension.structural");
            assertThat(row.getTitle()).isEqualTo("发现占位符");
            assertThat(row.getDescription()).contains("输出没有实质内容", "第一段", "补充工作结果");
        });
        assertThat(task.getValue()).satisfies(row -> {
            assertThat(row.getId()).isEqualTo("task-1");
            assertThat(row.getStatus()).isEqualTo("succeeded");
            assertThat(row.getProgress()).isEqualTo(100);
            assertThat(row.getResultSummary()).isEqualTo(summary);
        });
    }

    @Test
    void shouldKeepTaskUnfinishedIfSavingFindingFails() {
        when(deviations.insert(any(QualityDeviation.class))).thenThrow(new IllegalStateException("写入失败"));

        assertThatThrownBy(() -> manager.completeManualDeviationTask("task-1", request, List.of(finding), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tasks);
    }
}
