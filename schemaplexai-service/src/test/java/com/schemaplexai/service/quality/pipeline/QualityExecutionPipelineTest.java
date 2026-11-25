package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityRule;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import com.schemaplexai.service.quality.RuleConfigService;
import com.schemaplexai.service.quality.detector.DetectorRegistry;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategyRegistry;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import com.schemaplexai.service.quality.task.QualityTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QualityExecutionPipelineTest {

    private static final QualityCheckRequest REQUEST =
            QualityCheckRequest.forManual(null, "代码交付结论", "deviation", null, "tenant");

    private final DetectorRegistry detectors = mock(DetectorRegistry.class);
    private final QualityEvaluationStrategyRegistry strategies = mock(QualityEvaluationStrategyRegistry.class);
    private final QualityProfileResolverService profiles = mock(QualityProfileResolverService.class);
    private final RuleConfigService rules = mock(RuleConfigService.class);
    private final QualityTaskManager tasks = mock(QualityTaskManager.class);
    private final QualityExecutionPipeline pipeline = new QualityExecutionPipeline(
            detectors, strategies, profiles, mock(QualityReviewPolicyService.class),
            mock(QualityCrossReviewExecutionService.class), tasks, rules);

    @BeforeEach
    void returnsTheTaskIdProvidedByTheDetectionRequest() {
        when(tasks.createTask("task", "deviation", "manual", "manual", null, "tenant",
                null, null, null, null, null, Map.of("executionStrategy", "async"))).thenReturn("task");
    }

    @Test
    void recordsSkippedModelAndActualInvocationsOfDefaultDetectors() {
        QualityDetector clean = detector("structural");
        QualityDetector failed = detector("semantic");
        when(failed.detect(any())).thenThrow(new IllegalStateException("测试中的检测器失败"));
        when(detectors.allDetectors()).thenReturn(Map.of("structural", clean, "semantic", failed));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.taskStatus()).isEqualTo("succeeded");
        Map<String, Object> summary = savedSummary();
        assertThat(summary).containsEntry("score", 100).containsEntry("deviationCount", 0);
        assertThat(summary.get("coverage")).isEqualTo(Map.of(
                "rules", Map.of("mode", "all_detectors", "configuredRuleCount", 0, "enabledRuleCount", 0L,
                        "detectorsAttempted", 2, "detectorsCompleted", 1, "detectorsFailed", 1),
                "model", Map.of("status", "skipped", "reason", "no_profile")));
        verifyNoInteractions(strategies);
    }

    @Test
    void reportsConfiguredAndEnabledRulesApartFromTheDetectorsThatRan() {
        when(rules.list(null, "manual")).thenReturn(List.of(
                rule("structural", "active"), rule("structural", "inactive"), rule("semantic", "active")));
        QualityDetector matching = detector("structural");
        when(matching.supports("structural")).thenReturn(true);
        QualityDetector unmatched = detector("other");
        when(detectors.allDetectors()).thenReturn(Map.of("structural", matching, "other", unmatched));
        when(matching.detect(any())).thenReturn(new QualityDetector.DetectionResult(
                true, "warning", "交付结论缺少证据", Map.of()));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.deviationCount()).isEqualTo(1);
        Map<String, Object> summary = savedSummary();
        assertThat(summary).containsEntry("deviationCount", 1).containsEntry("warningCount", 1L);
        assertThat(summary.get("coverage")).isEqualTo(Map.of(
                "rules", Map.of("mode", "configured", "configuredRuleCount", 3, "enabledRuleCount", 2L,
                        "detectorsAttempted", 1, "detectorsCompleted", 1, "detectorsFailed", 0),
                "model", Map.of("status", "skipped", "reason", "no_profile")));
    }

    @Test
    void recordsResolvedProfileWhenNoModelIsConfigured() {
        stubProfile();

        pipeline.execute(REQUEST, "task");

        assertModelEvidence(Map.of("status", "skipped", "reason", "no_model", "resolvedProfileId", "profile"));
        verifyNoInteractions(strategies);
    }

    @Test
    void preservesStrategyScoreAndDoesNotConfirmAnUnverifiedModelRun() {
        QualityEvaluationStrategy strategy = stubModel();
        when(strategy.evaluate(any())).thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                false, 96, "通过", List.of()));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(96);
        assertModelEvidence(Map.of("status", "unknown", "reason", "unverified_result",
                "resolvedProfileId", "profile", "resolvedModelId", "model"));
    }

    @Test
    void persistsCompletedAssessmentBasedOnDecodedModelEvidence() {
        QualityEvaluationStrategy strategy = stubModel();
        when(strategy.evaluate(any())).thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                false, 96, "通过", List.of()).withModelAssessment("completed", null));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(96);
        assertModelEvidence(Map.of("status", "completed", "resolvedProfileId", "profile", "resolvedModelId", "model"));
    }

    @Test
    void persistsKnownModelFailureSeparatelyFromTheTaskOutcome() {
        QualityEvaluationStrategy strategy = stubModel();
        when(strategy.evaluate(any())).thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                true, 20, "评审失败", List.of()).withModelAssessment("failed", "model_response_invalid"));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(20);
        assertThat(result.taskStatus()).isEqualTo("succeeded");
        assertModelEvidence(Map.of("status", "failed", "reason", "model_response_invalid",
                "resolvedProfileId", "profile", "resolvedModelId", "model"));
    }

    @Test
    void recordsModelInfrastructureSignalWithoutChangingFindingsOrScore() {
        QualityEvaluationStrategy strategy = stubModel();
        QualityEvaluationStrategy.Finding finding = new QualityEvaluationStrategy.Finding(
                "omission", "critical", 100, "qa.infrastructure.model_response_invalid",
                "模型响应不可解析", "没有可用的评审结果", null, null);
        when(strategy.evaluate(any())).thenReturn(new QualityEvaluationStrategy.EvaluationResult(
                true, 20, "评审失败", List.of(finding)));

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(20);
        assertThat(result.findings()).containsExactly(finding);
        assertModelEvidence(Map.of("status", "unknown", "reason", "model_response_invalid",
                "resolvedProfileId", "profile", "resolvedModelId", "model"));
    }

    @Test
    void recordsUnknownWhenConfiguredStrategyReturnsNoResult() {
        stubModel();

        QualityCheckResult result = pipeline.execute(REQUEST, "task");

        assertThat(result.score()).isEqualTo(100);
        assertModelEvidence(Map.of("status", "unknown", "reason", "no_result",
                "resolvedProfileId", "profile", "resolvedModelId", "model"));
    }

    private Map<String, Object> savedSummary() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.captor();
        verify(tasks).completeManualDeviationTask(eq("task"), eq(REQUEST), any(), captor.capture());
        return captor.getValue();
    }

    private void assertModelEvidence(Map<String, String> expected) {
        assertThat(((Map<?, ?>) savedSummary().get("coverage")).get("model")).isEqualTo(expected);
    }

    private void stubProfile() {
        QualityProfile profile = new QualityProfile();
        profile.setId("profile");
        when(profiles.resolveProfile(null, null, null, "deviation", "manual")).thenReturn(profile);
    }

    private QualityEvaluationStrategy stubModel() {
        stubProfile();
        when(profiles.listModelIds("profile")).thenReturn(List.of("model"));
        QualityEvaluationStrategy strategy = mock(QualityEvaluationStrategy.class);
        when(strategies.getStrategy("deviation")).thenReturn(strategy);
        return strategy;
    }

    private QualityDetector detector(String name) {
        QualityDetector detector = mock(QualityDetector.class);
        when(detector.getType()).thenReturn(name);
        return detector;
    }

    private QualityRule rule(String dimension, String status) {
        QualityRule rule = new QualityRule();
        rule.setDimensionCode(dimension);
        rule.setStatus(status);
        return rule;
    }
}
