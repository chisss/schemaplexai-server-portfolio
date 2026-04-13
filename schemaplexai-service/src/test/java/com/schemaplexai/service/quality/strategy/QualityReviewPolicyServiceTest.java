package com.schemaplexai.service.quality.strategy;

import com.schemaplexai.dao.mapper.QualityRuleMapper;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityReviewPolicyServiceTest {

    private final QualityRuleMapper qualityRuleMapper = mock(QualityRuleMapper.class);
    private final QualityReviewPolicyService service = new QualityReviewPolicyService(qualityRuleMapper);

    @Test
    void shouldResolvePolicyAndRenderRulePrompt() {
        QualityProfile profile = new QualityProfile();
        profile.setTenantId("tenant-1");
        profile.setEnabledDimensionCodes(List.of("omission", "ambiguity"));
        profile.setEnabledRuleCodes(List.of("RULE-ACCEPTANCE"));
        profile.setThresholdConfig(Map.of(
                "omission", Map.of(
                        "enabled", true,
                        "confidenceThreshold", 85,
                        "maxFindings", 1,
                        "evaluationPrompt", "必须检查验收标准是否明确"
                )
        ));

        QualityRule rule = new QualityRule();
        rule.setCode("RULE-ACCEPTANCE");
        rule.setName("验收标准完整性");
        rule.setDimensionCode("omission");
        rule.setSeverity("critical");
        rule.setWeight(120);
        rule.setConditionExpr("必须包含可验证的验收标准");
        rule.setRuleConfig(Map.of(
                "prompt", "若文档缺少验收标准，必须指出风险",
                "focusKeywords", List.of("验收标准", "成功指标")
        ));
        when(qualityRuleMapper.selectList(any())).thenReturn(List.of(rule));

        QualityReviewPolicy policy = service.resolvePolicy(profile, "intent_defect", "event");

        assertThat(policy.enabledDimensions()).contains("omission", "ambiguity");
        assertThat(policy.dimensionConfig("omission").confidenceThreshold()).isEqualTo(85);
        assertThat(policy.rules()).hasSize(1);
        assertThat(service.renderPromptBlock(policy))
                .contains("RULE-ACCEPTANCE")
                .contains("必须包含可验证的验收标准")
                .contains("验收标准");
    }

    @Test
    void shouldFilterLowConfidenceAndEnforceDimensionLimit() {
        QualityProfile profile = new QualityProfile();
        profile.setEnabledDimensionCodes(List.of("omission", "ambiguity"));
        profile.setThresholdConfig(Map.of(
                "omission", Map.of(
                        "enabled", true,
                        "confidenceThreshold", 85,
                        "maxFindings", 1
                ),
                "ambiguity", Map.of(
                        "enabled", true,
                        "confidenceThreshold", 60,
                        "maxFindings", 2
                )
        ));
        when(qualityRuleMapper.selectList(any())).thenReturn(List.of());

        QualityReviewPolicy policy = service.resolvePolicy(profile, "intent_defect", "event");
        QualityEvaluationStrategy.EvaluationResult filtered = service.applyPolicy(
                new QualityEvaluationStrategy.EvaluationResult(
                        true,
                        72,
                        "发现多个问题",
                        List.of(
                                new QualityEvaluationStrategy.Finding("omission", "critical", 92,
                                        "RULE-1", "缺少验收标准", "未定义验收标准", "requirements#acceptance", "补充验收标准"),
                                new QualityEvaluationStrategy.Finding("omission", "warning", 70,
                                        "RULE-2", "遗漏异常路径", "未覆盖异常处理", "requirements#exception", "补充异常场景"),
                                new QualityEvaluationStrategy.Finding("ambiguity", "warning", 66,
                                        "RULE-3", "表达模糊", "性能要求未量化", "requirements#performance", "补充明确指标")
                        )
                ),
                policy
        );

        assertThat(filtered.hasIssue()).isTrue();
        assertThat(filtered.findings()).hasSize(2);
        assertThat(filtered.findings()).extracting(QualityEvaluationStrategy.Finding::title)
                .containsExactly("缺少验收标准", "表达模糊");
    }

    @Test
    void shouldKeepReviewSignalWhenAllFindingsAreFilteredOut() {
        QualityProfile profile = new QualityProfile();
        profile.setEnabledDimensionCodes(List.of("omission"));
        profile.setThresholdConfig(Map.of(
                "omission", Map.of(
                        "enabled", true,
                        "confidenceThreshold", 90,
                        "maxFindings", 1
                )
        ));
        when(qualityRuleMapper.selectList(any())).thenReturn(List.of());

        QualityReviewPolicy policy = service.resolvePolicy(profile, "intent_defect", "event");
        QualityEvaluationStrategy.EvaluationResult filtered = service.applyPolicy(
                new QualityEvaluationStrategy.EvaluationResult(
                        true,
                        94,
                        "发现潜在问题",
                        List.of(new QualityEvaluationStrategy.Finding(
                                "omission",
                                "warning",
                                72,
                                "RULE-LOW-CONFIDENCE",
                                "异常路径覆盖不足",
                                "文档未充分说明失败补偿与异常回滚",
                                "design#rollback",
                                "补充异常处理与回滚策略"
                        ))
                ),
                policy
        );

        assertThat(filtered.hasIssue()).isTrue();
        assertThat(filtered.score()).isLessThan(80);
        assertThat(filtered.findings()).hasSize(1);
        assertThat(filtered.summary()).contains("人工复核");
        assertThat(filtered.findings().getFirst().description()).contains("原始置信度=72");
    }
}
