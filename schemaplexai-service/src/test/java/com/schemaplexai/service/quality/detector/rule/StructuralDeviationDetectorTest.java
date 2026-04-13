package com.schemaplexai.service.quality.detector.rule;

import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralDeviationDetectorTest {

    // 内联 stub，避免引入 mockito 依赖
    private final ModelBasedQualityEvaluator stubEvaluator = new ModelBasedQualityEvaluator(null, null, null) {
        @Override
        public QualityEvaluationStrategy.EvaluationResult evaluate(String modelId, String systemPrompt, String userPrompt) {
            if (userPrompt.contains("TODO")) {
                return new QualityEvaluationStrategy.EvaluationResult(true, 60, "发现结构问题",
                        List.of(new QualityEvaluationStrategy.Finding("placeholder", "warning", 85,
                                "qa.dimension.structural", "存在占位符", "内容包含TODO", "", "")));
            }
            return new QualityEvaluationStrategy.EvaluationResult(false, 95, "结构质量良好", List.of());
        }
    };

    private final StructuralDeviationDetector detector = new StructuralDeviationDetector(stubEvaluator);

    @Test
    void shouldFlagIssueWhenModelReturnsFindings() {
        QualityDetector.DetectionResult result = detector.detect(new QualityDetector.DetectionContext(
                null, null, "structural", null,
                Map.of("modelId", "test-model-id"),
                "TODO: 待补充最终实现方案"
        ));
        assertThat(result.hasIssue()).isTrue();
        assertThat(result.message()).isEqualTo("发现结构问题");
    }

    @Test
    void shouldPassWhenModelReturnsNoFindings() {
        QualityDetector.DetectionResult result = detector.detect(new QualityDetector.DetectionContext(
                null, null, "structural", null,
                Map.of("modelId", "test-model-id"),
                "## 目标\n- 完成接口联调"
        ));
        assertThat(result.hasIssue()).isFalse();
    }

    @Test
    void shouldReturnEmptyWhenNoModelConfigured() {
        QualityDetector.DetectionResult result = detector.detect(new QualityDetector.DetectionContext(
                null, null, "structural", null, Map.of(), "some content"
        ));
        assertThat(result.hasIssue()).isFalse();
    }
}
