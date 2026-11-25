package com.schemaplexai.service.quality.strategy.impl;

import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy.EvaluationContext;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeviationEvaluationStrategyTest {

    @Test
    void shouldEvaluateManualOutputAgainstTheOriginalAgentTask() {
        var evaluator = mock(ModelBasedQualityEvaluator.class);
        var strategy = new DeviationEvaluationStrategy(evaluator, mock(QualityReviewPolicyService.class));
        var context = new EvaluationContext(null, "deviation", null, "manual_quality_check",
                "偏离检测", "报价为 59.97", "保留两位小数", "model-1", null,
                Map.of("sourceType", "manual", "originalTask", "计算 19.99 × 3"), null);

        strategy.evaluate(context);

        var systemPrompt = ArgumentCaptor.forClass(String.class);
        var userPrompt = ArgumentCaptor.forClass(String.class);
        verify(evaluator).evaluate(eq("model-1"), systemPrompt.capture(), userPrompt.capture());
        assertThat(systemPrompt.getValue()).contains("以原始任务中明确的要求评估任务符合度", "请严格按照以下 JSON 格式");
        assertThat(userPrompt.getValue()).contains("【参考规范 / Spec 上下文】\n保留两位小数",
                "【原始 Agent 任务】\n计算 19.99 × 3", "【输出内容】\n报价为 59.97");
    }
}
