package com.schemaplexai.service.quality.gate;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class QualityGateCoverageTest {

    private final QualityGateEvaluator evaluator = new QualityGateEvaluator();

    @Test
    void shouldNotPassIncompleteCoverageEvenWhenConfiguredToIgnoreTaskFailure() {
        QualityGateDecision decision = evaluator.evaluate(QualityGateContext.of(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 100,
                        "qualityGateDecision", "error",
                        "qualityCoverage", incompleteCoverage()
                ),
                Map.of("blockOnQualityTaskFailed", false)
        ));

        assertThat(decision.pauseWorkflow()).isTrue();
        assertThat(decision.message()).contains("质量检查覆盖不完整", "必需质量维度未通过");
    }

    @Test
    void shouldKeepExplicitWarnModeVisibleWithoutReturningPass() {
        QualityGateDecision decision = evaluator.evaluate(QualityGateContext.of(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 100,
                        "qualityCoverage", incompleteCoverage()
                ),
                Map.of("qualityGateMode", "warn")
        ));

        assertThat(decision.decision()).isEqualTo("warn");
        assertThat(decision.message()).contains("质量检查覆盖不完整");
    }

    @Test
    void shouldPassOnlyWhenRequiredCoverageIsComplete() {
        QualityGateDecision decision = evaluator.evaluate(QualityGateContext.of(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 100,
                        "qualityCoverage", Map.of(
                                "status", "complete",
                                "requiredIncomplete", false,
                                "dimensions", Map.of(
                                        "structural", Map.of("required", true, "status", "passed")
                                ),
                                "model", Map.of("required", false, "status", "not_applicable")
                        )
                ),
                Map.of()
        ));

        assertThat(decision.decision()).isEqualTo("pass");
    }

    private Map<String, Object> incompleteCoverage() {
        return Map.of(
                "status", "incomplete",
                "requiredIncomplete", true,
                "dimensions", Map.of(
                        "structural", Map.of(
                                "required", true,
                                "status", "unknown",
                                "reason", "detector_error")
                ),
                "model", Map.of(
                        "required", true,
                        "status", "unknown",
                        "reason", "timeout")
        );
    }
}
