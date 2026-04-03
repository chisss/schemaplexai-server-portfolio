package com.schemaplexai.service.quality.detector.rule;

import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.service.quality.detector.QualityDetector;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralDeviationDetectorTest {

    private final StructuralDeviationDetector detector = new StructuralDeviationDetector();

    @Test
    void shouldFlagPlaceholderContentAsStructuralIssue() {
        QualityDetector.DetectionResult result = detector.detect(new QualityDetector.DetectionContext(
                null,
                null,
                "structural",
                null,
                Map.of(),
                "TODO: 待补充最终实现方案"
        ));

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.severity()).isEqualTo(DeviationSeverityEnum.WARNING.getCode());
        assertThat(result.message()).contains("结构不完整");
    }

    @Test
    void shouldPassForStructuredContent() {
        QualityDetector.DetectionResult result = detector.detect(new QualityDetector.DetectionContext(
                null,
                null,
                "structural",
                null,
                Map.of(),
                "## 目标\n- 完成接口联调\n## 方案\n1. 更新后端\n2. 更新前端\n## 验证\n- 运行测试"
        ));

        assertThat(result.hasIssue()).isFalse();
        assertThat(result.severity()).isEqualTo(DeviationSeverityEnum.INFO.getCode());
    }
}
