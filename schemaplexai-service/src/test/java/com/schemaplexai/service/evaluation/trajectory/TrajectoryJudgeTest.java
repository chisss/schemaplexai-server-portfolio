package com.schemaplexai.service.evaluation.trajectory;

import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrajectoryJudgeTest {

    private final ModelBasedQualityEvaluator modelEvaluator = mock(ModelBasedQualityEvaluator.class);
    private final TrajectoryJudge judge = new TrajectoryJudge(modelEvaluator);

    private static QualityEvaluationStrategy.Finding finding(String type, String title) {
        return new QualityEvaluationStrategy.Finding(
                type, "warning", 90, "rule-" + type, title, "问题描述", "round 2", "改进建议");
    }

    @Test
    void shouldMergeConsensusFindingsFromTwoJudges() {
        when(modelEvaluator.evaluate(eq("judge-1"), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                        true, 70, "存在问题",
                        List.of(finding("hallucination", "虚构工具返回"),
                                finding("tool_selection", "选择了错误工具"))));
        when(modelEvaluator.evaluate(eq("judge-2"), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                        true, 80, "存在问题",
                        List.of(finding("hallucination", "虚构工具返回"))));

        TrajectoryJudge.JudgeOutcome outcome = judge.judge(
                List.of("judge-1", "judge-2"), "用户问题", "期望基线", "【对话轨迹】...");

        assertThat(outcome.getScore()).isEqualTo(75);
        assertThat(outcome.isHasIssue()).isTrue();
        assertThat(outcome.getConsensusFindingCount()).isEqualTo(1);
        assertThat(outcome.getUniqueFindingCount()).isEqualTo(2);
        assertThat(outcome.getFindings()).hasSize(2);
        // 共识问题排在最前
        assertThat(outcome.getFindings().get(0)).containsEntry("consensusCount", 2);
        assertThat(outcome.getFindings().get(0)).containsEntry("title", "虚构工具返回");
        assertThat(outcome.getFindings().get(1)).containsEntry("consensusCount", 1);
        assertThat(outcome.getModelVerdicts()).hasSize(2);
    }

    @Test
    void shouldReturnNoIssueWhenBothJudgesClean() {
        when(modelEvaluator.evaluate(anyString(), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(false, 95, "未发现问题", List.of()));

        TrajectoryJudge.JudgeOutcome outcome = judge.judge(
                List.of("judge-1", "judge-2"), "用户问题", null, "【对话轨迹】...");

        assertThat(outcome.getScore()).isEqualTo(95);
        assertThat(outcome.isHasIssue()).isFalse();
        assertThat(outcome.getFindings()).isEmpty();
    }

    @Test
    void shouldHandleJudgeModelFailure() {
        when(modelEvaluator.evaluate(eq("judge-1"), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(false, 90, "ok", List.of()));
        when(modelEvaluator.evaluate(eq("judge-2"), anyString(), anyString()))
                .thenThrow(new IllegalStateException("网络错误"));

        TrajectoryJudge.JudgeOutcome outcome = judge.judge(
                List.of("judge-1", "judge-2"), "用户问题", null, "轨迹");

        assertThat(outcome.isHasIssue()).isTrue();
        assertThat(outcome.getModelVerdicts()).hasSize(2);
        assertThat(outcome.getModelVerdicts().stream().anyMatch(TrajectoryJudge.ModelVerdict::isFailed)).isTrue();
    }

    @Test
    void shouldReturnZeroWhenNoJudgeModels() {
        TrajectoryJudge.JudgeOutcome outcome = judge.judge(List.of(), "问题", null, "轨迹");
        assertThat(outcome.getScore()).isZero();
        assertThat(outcome.getSummary()).contains("未配置");
    }

    @Test
    void shouldInjectTrajectoryPromptWithFiveDimensionRubric() {
        when(modelEvaluator.evaluate(anyString(), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(false, 90, "ok", List.of()));

        judge.judge(List.of("judge-1"), "帮我查天气", "期望: 晴天", "【对话轨迹】USER: 问题");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(modelEvaluator).evaluate(eq("judge-1"), anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue())
                .contains("【用户问题】")
                .contains("【参考基线 / 期望输出】")
                .contains("【Agent 完整执行轨迹】")
                .contains("【对话轨迹】");
    }

    @Test
    void shouldTruncateToTwoJudgeModels() {
        when(modelEvaluator.evaluate(anyString(), anyString(), anyString()))
                .thenReturn(new QualityEvaluationStrategy.EvaluationResult(false, 90, "ok", List.of()));

        judge.judge(List.of("judge-1", "judge-2", "judge-3"), "问题", null, "轨迹");

        assertThat(judge.judge(List.of("judge-1", "judge-2", "judge-3"), "问题", null, "轨迹")
                .getModelVerdicts()).hasSize(2);
    }
}
