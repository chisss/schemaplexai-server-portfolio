package com.schemaplexai.service.evaluation.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.EvalDatasetItemMapper;
import com.schemaplexai.dao.mapper.EvalDatasetMapper;
import com.schemaplexai.dao.mapper.EvalResultMapper;
import com.schemaplexai.dao.mapper.EvalTaskMapper;
import com.schemaplexai.model.converter.EvaluationConverter;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.EvalDatasetItem;
import com.schemaplexai.model.entity.EvalResult;
import com.schemaplexai.model.entity.EvalTask;
import com.schemaplexai.model.vo.evaluation.EvalTaskVerdictVO;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.evaluation.trajectory.EvalAgentExecutionBridge;
import com.schemaplexai.service.evaluation.trajectory.TrajectoryCollector;
import com.schemaplexai.service.evaluation.trajectory.TrajectoryJudge;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.concurrent.Executor;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalTaskServiceImplTest {

    @Mock
    private EvalTaskMapper evalTaskMapper;
    @Mock
    private EvalDatasetMapper evalDatasetMapper;
    @Mock
    private EvalDatasetItemMapper evalDatasetItemMapper;
    @Mock
    private EvalResultMapper evalResultMapper;
    @Mock
    private AiModelMapper aiModelMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private EvaluationConverter evaluationConverter;
    @Mock
    private EvalMetricCalculator evalMetricCalculator;
    @Mock
    private AIModelRouter aiModelRouter;
    @Mock
    private EvalAgentExecutionBridge evalAgentExecutionBridge;
    @Mock
    private TrajectoryCollector trajectoryCollector;
    @Mock
    private TrajectoryJudge trajectoryJudge;
    @Mock
    private ModelBasedQualityEvaluator modelBasedQualityEvaluator;

    private final Executor directExecutor = Runnable::run;

    private EvalTaskServiceImpl buildService() {
        MockitoAnnotations.openMocks(this);
        return new EvalTaskServiceImpl(
                evalTaskMapper, evalDatasetMapper, evalDatasetItemMapper, evalResultMapper,
                aiModelMapper, agentMapper, evaluationConverter, evalMetricCalculator,
                aiModelRouter, evalAgentExecutionBridge, trajectoryCollector, trajectoryJudge,
                modelBasedQualityEvaluator, new EvalExecutionScheduler(directExecutor, directExecutor,
                        mock(PlatformTransactionManager.class)));
    }

    @Test
    void shouldResolveFinalStatusThreeStates() {
        assertThat(EvalTaskServiceImpl.resolveFinalStatus(5, 0)).isEqualTo("DONE");
        assertThat(EvalTaskServiceImpl.resolveFinalStatus(0, 5)).isEqualTo("FAILED");
        assertThat(EvalTaskServiceImpl.resolveFinalStatus(3, 2)).isEqualTo("PARTIAL");
    }

    @Test
    void shouldSkipCompletedCombinationsOnResume() {
        EvalTaskServiceImpl service = buildService();
        EvalTask task = new EvalTask();
        task.setId("task-1");
        task.setTaskType("QA");
        task.setModelIds(List.of("model-1", "model-2"));

        EvalDatasetItem itemA = new EvalDatasetItem();
        itemA.setId("item-A");
        itemA.setInputText("问题A");
        EvalDatasetItem itemB = new EvalDatasetItem();
        itemB.setId("item-B");
        itemB.setInputText("问题B");

        AiModel model1 = new AiModel();
        model1.setId("model-1");
        model1.setName("模型1");
        AiModel model2 = new AiModel();
        model2.setId("model-2");
        model2.setName("模型2");
        when(aiModelMapper.selectBatchIds(task.getModelIds()))
                .thenReturn(List.of(model1, model2));

        // 已有结果：item-A × model-1 完成 → 续跑应跳过该组合
        EvalResult existing = new EvalResult();
        existing.setTaskId("task-1");
        existing.setItemId("item-A");
        existing.setModelId("model-1");
        when(evalResultMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(existing));

        List<EvalTaskServiceImpl.WorkItem> pending = service.buildPendingWorkItemsForTest(
                task, List.of(itemA, itemB));

        // 4 组合 - 1 已完成 = 3 待执行
        assertThat(pending).hasSize(3);
        boolean skipped = pending.stream().noneMatch(work ->
                work.item().getId().equals("item-A") && work.model() != null
                        && work.model().getId().equals("model-1"));
        assertThat(skipped).isTrue();
    }

    @Test
    void shouldBuildVerdictPassWhenAllAboveThreshold() {
        EvalTaskServiceImpl service = buildService();
        EvalTask task = new EvalTask();
        task.setId("task-1");
        task.setStatus("DONE");
        task.setPassScoreThreshold(80);
        when(evalTaskMapper.selectById("task-1")).thenReturn(task);
        when(evalResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                result("r1", 90, "SUCCESS"), result("r2", 85, "SUCCESS")));

        EvalTaskVerdictVO verdict = service.verdict("task-1", null);

        assertThat(verdict.isEnabled()).isTrue();
        assertThat(verdict.isPass()).isTrue();
        assertThat(verdict.getPassRate()).isEqualTo(100.0);
        assertThat(verdict.getBelowThresholdCount()).isZero();
    }

    @Test
    void shouldBuildVerdictFailWhenBelowThresholdOrNotDone() {
        EvalTaskServiceImpl service = buildService();
        EvalTask task = new EvalTask();
        task.setId("task-2");
        task.setStatus("PARTIAL");
        task.setPassScoreThreshold(80);
        when(evalTaskMapper.selectById("task-2")).thenReturn(task);
        when(evalResultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                result("r1", 90, "SUCCESS"), result("r2", 60, "SUCCESS"), result("r3", 0, "FAILED")));

        EvalTaskVerdictVO verdict = service.verdict("task-2", null);

        assertThat(verdict.isPass()).isFalse();
        assertThat(verdict.getPassRate()).isEqualTo(100.0 / 3);
        assertThat(verdict.getBelowThresholdCount()).isEqualTo(1);
        assertThat(verdict.getInvokeFailCount()).isEqualTo(1);
        assertThat(verdict.getReason()).contains("任务未完成");
    }

    @Test
    void shouldReturnDisabledVerdictWhenNoThreshold() {
        EvalTaskServiceImpl service = buildService();
        EvalTask task = new EvalTask();
        task.setId("task-3");
        task.setStatus("DONE");
        task.setPassScoreThreshold(null);
        when(evalTaskMapper.selectById("task-3")).thenReturn(task);

        EvalTaskVerdictVO verdict = service.verdict("task-3", null);

        assertThat(verdict.isEnabled()).isFalse();
        assertThat(verdict.isPass()).isFalse();
        assertThat(verdict.getReason()).contains("未配置");
    }

    private static EvalResult result(String id, int score, String status) {
        EvalResult result = new EvalResult();
        result.setId(id);
        result.setScore(score);
        result.setStatus(status);
        return result;
    }

    @Test
    void shouldSkipCompletedAgentCombinationsForTrajectoryMode() {
        EvalTaskServiceImpl service = buildService();
        EvalTask task = new EvalTask();
        task.setId("task-2");
        task.setTaskType("TRAJECTORY");
        task.setAgentIds(List.of("agent-1"));

        EvalDatasetItem item = new EvalDatasetItem();
        item.setId("item-A");
        item.setInputText("问题A");

        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setName("Agent1");
        when(agentMapper.selectBatchIds(task.getAgentIds())).thenReturn(List.of(agent));

        EvalResult existing = new EvalResult();
        existing.setTaskId("task-2");
        existing.setItemId("item-A");
        existing.setModelId("agent-1");
        when(evalResultMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(existing));

        List<EvalTaskServiceImpl.WorkItem> pending = service.buildPendingWorkItemsForTest(
                task, List.of(item));

        assertThat(pending).isEmpty();
        assertThat(pending.stream().noneMatch(work ->
                work.item().getId().equals("item-A"))).isTrue();
    }
}
