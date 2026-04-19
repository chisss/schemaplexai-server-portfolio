package com.schemaplexai.service.quality.impl;

import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.service.quality.strategy.ModelBasedQualityEvaluator;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityCrossReviewExecutionServiceImplTest {

    private final CrossReviewMapper crossReviewMapper = mock(CrossReviewMapper.class);
    private final SpecDocumentMapper specDocumentMapper = mock(SpecDocumentMapper.class);
    private final QualityProfileMapper qualityProfileMapper = mock(QualityProfileMapper.class);
    private final SpecMapper specMapper = mock(SpecMapper.class);
    private final ModelBasedQualityEvaluator modelEvaluator = mock(ModelBasedQualityEvaluator.class);
    private final QualityReviewPolicyService reviewPolicyService = mock(QualityReviewPolicyService.class);

    private final QualityCrossReviewExecutionServiceImpl service = new QualityCrossReviewExecutionServiceImpl(
            crossReviewMapper, specDocumentMapper, qualityProfileMapper, specMapper, modelEvaluator, reviewPolicyService
    );

    @Test
    void shouldMergeConsensusAndUniqueFindingsFromMultipleModels() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-1");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setModelBResult(patch.getModelBResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-1")).thenAnswer(invocation -> stored.get());

        QualityReviewPolicy policy = QualityReviewPolicy.empty("deviation", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));

        QualityEvaluationStrategy.EvaluationResult resultA = new QualityEvaluationStrategy.EvaluationResult(
                true,
                78,
                "模型A发现问题",
                List.of(
                        new QualityEvaluationStrategy.Finding("omission", "critical", 91,
                                "RULE-1", "缺少验收标准", "缺少明确验收标准", "requirements#acceptance", "补充验收标准")
                )
        );
        QualityEvaluationStrategy.EvaluationResult resultB = new QualityEvaluationStrategy.EvaluationResult(
                true,
                74,
                "模型B发现问题",
                List.of(
                        new QualityEvaluationStrategy.Finding("omission", "critical", 88,
                                "RULE-1", "缺少验收标准", "缺少明确验收标准", "requirements#acceptance", "补充验收标准"),
                        new QualityEvaluationStrategy.Finding("ambiguity", "warning", 79,
                                "RULE-2", "性能目标模糊", "未给出量化指标", "requirements#performance", "补充 SLO 指标")
                )
        );
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenReturn(resultA);
        when(modelEvaluator.evaluate(eq("model-b"), anyString(), anyString())).thenReturn(resultB);

        CrossReview review = service.createAndExecute(
                "spec-1", "task-1", "profile-1", "deviation",
                List.of("model-a", "model-b"), "manual", null, "待审查内容", null, "tenant-1"
        );

        assertThat(review).isNotNull();
        assertThat(review.getTenantId()).isEqualTo("tenant-1");
        assertThat(review.getSummary()).containsEntry("modelCount", 2);
        assertThat(String.valueOf(review.getSummary().get("overview"))).contains("共识问题 1 项");
        assertThat(review.getMergedResult()).containsEntry("uniqueFindingCount", 2);
        assertThat((List<?>) review.getMergedResult().get("findings")).hasSize(2);
    }

    @Test
    void shouldFallbackToProfileTenantWhenExplicitTenantMissing() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-2");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-2")).thenAnswer(invocation -> stored.get());

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-2");
        profile.setTenantId("tenant-from-profile");
        when(qualityProfileMapper.selectById("profile-2")).thenReturn(profile);

        QualityReviewPolicy policy = QualityReviewPolicy.empty("intent_defect", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenReturn(
                new QualityEvaluationStrategy.EvaluationResult(false, 95, "未发现问题", List.of())
        );

        CrossReview review = service.createAndExecute(
                "spec-1", "task-2", "profile-2", "intent_defect",
                List.of("model-a"), "manual", null, "待审查内容", null, null
        );

        assertThat(review).isNotNull();
        assertThat(review.getTenantId()).isEqualTo("tenant-from-profile");
    }

    @Test
    void shouldTrimReviewersToTwoWhenMoreModelsAreProvided() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-3");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setModelBResult(patch.getModelBResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-3")).thenAnswer(invocation -> stored.get());

        QualityReviewPolicy policy = QualityReviewPolicy.empty("deviation", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenReturn(
                new QualityEvaluationStrategy.EvaluationResult(false, 95, "模型A正常", List.of())
        );
        when(modelEvaluator.evaluate(eq("model-b"), anyString(), anyString())).thenReturn(
                new QualityEvaluationStrategy.EvaluationResult(false, 92, "模型B正常", List.of())
        );

        CrossReview review = service.createAndExecute(
                "spec-1", "task-3", "profile-3", "deviation",
                List.of("model-a", "model-b", "model-c", "model-a"),
                "manual", null, "待审查内容", null, "tenant-1"
        );

        assertThat(review).isNotNull();
        assertThat(review.getModelAId()).isEqualTo("model-a");
        assertThat(review.getModelBId()).isEqualTo("model-b");
        assertThat(review.getSummary()).containsEntry("modelCount", 2);
    }

    @Test
    void shouldCompactLargeReviewPromptBeforeInvokingModel() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        AtomicReference<String> capturedUserPrompt = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-4");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-4")).thenAnswer(invocation -> stored.get());

        QualityReviewPolicy policy = QualityReviewPolicy.empty("intent_defect", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenAnswer(invocation -> {
            capturedUserPrompt.set(invocation.getArgument(2));
            return new QualityEvaluationStrategy.EvaluationResult(false, 96, "模型A正常", List.of());
        });

        String largeContent = "头部约束\n" + "A".repeat(8_000) + "\n结尾验收\n" + "B".repeat(4_000);
        service.createAndExecute(
                "spec-1", "task-4", "profile-4", "intent_defect",
                List.of("model-a"), "manual", null, largeContent, null, "tenant-1"
        );

        assertThat(capturedUserPrompt.get()).contains("中间内容已截断");
        assertThat(capturedUserPrompt.get()).contains("头部约束");
        assertThat(capturedUserPrompt.get()).contains("BBBBBBBB");
        assertThat(capturedUserPrompt.get().length()).isLessThan(6_000);
    }

    @Test
    void shouldUseMarketingReviewFocusForMarketingSpecCrossReview() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        AtomicReference<String> capturedUserPrompt = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-marketing");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-marketing")).thenAnswer(invocation -> stored.get());

        Spec marketingSpec = new Spec();
        marketingSpec.setId("spec-marketing");
        marketingSpec.setSpecType("marketing");
        when(specMapper.selectById("spec-marketing")).thenReturn(marketingSpec);

        QualityReviewPolicy policy = QualityReviewPolicy.empty("deviation", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenAnswer(invocation -> {
            capturedUserPrompt.set(invocation.getArgument(2));
            return new QualityEvaluationStrategy.EvaluationResult(false, 93, "营销文案审查通过", List.of());
        });

        service.createAndExecute(
                "spec-marketing", "task-marketing", null, "deviation",
                List.of("model-a"), "manual", null, "营销终稿内容", null, "tenant-1"
        );

        assertThat(capturedUserPrompt.get()).contains("营销交付物 / 最终文案");
        assertThat(capturedUserPrompt.get()).contains("夸大承诺");
        assertThat(capturedUserPrompt.get()).contains("合规风险提示");
        assertThat(capturedUserPrompt.get()).doesNotContain("接口契约");
        assertThat(capturedUserPrompt.get()).doesNotContain("幂等性");
    }

    @Test
    void shouldPreserveIssueSignalWhenReviewerHasIssueButNoStructuredFindings() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-5");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-5")).thenAnswer(invocation -> stored.get());

        QualityReviewPolicy policy = QualityReviewPolicy.empty("intent_defect", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenReturn(
                new QualityEvaluationStrategy.EvaluationResult(true, 30, "接口契约和异常路径均缺失", List.of())
        );

        CrossReview review = service.createAndExecute(
                "spec-1", "task-5", "profile-5", "intent_defect",
                List.of("model-a"), "manual", null, "待审查内容", null, "tenant-1"
        );

        assertThat(review).isNotNull();
        assertThat(review.getMergedResult()).containsEntry("hasIssue", true);
        assertThat(review.getMergedResult()).containsEntry("uniqueFindingCount", 0);
    }

    @Test
    void shouldDegradeFailedReviewerToFindingInsteadOfFailingWholeCrossReview() {
        AtomicReference<CrossReview> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            CrossReview entity = invocation.getArgument(0);
            entity.setId("review-6");
            stored.set(entity);
            return 1;
        }).when(crossReviewMapper).insert(any(CrossReview.class));
        doAnswer(invocation -> {
            CrossReview patch = invocation.getArgument(0);
            CrossReview current = stored.get();
            current.setModelAResult(patch.getModelAResult());
            current.setModelBResult(patch.getModelBResult());
            current.setMergedResult(patch.getMergedResult());
            current.setSummary(patch.getSummary());
            current.setStatus(patch.getStatus());
            current.setCompletedAt(patch.getCompletedAt());
            current.setErrorMessage(patch.getErrorMessage());
            return 1;
        }).when(crossReviewMapper).updateById(any(CrossReview.class));
        when(crossReviewMapper.selectById("review-6")).thenAnswer(invocation -> stored.get());

        QualityReviewPolicy policy = QualityReviewPolicy.empty("deviation", "manual");
        when(reviewPolicyService.resolvePolicy(any(), anyString(), anyString())).thenReturn(policy);
        when(reviewPolicyService.applyPolicy(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewPolicyService.normalizeFinding(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(modelEvaluator.evaluate(eq("model-a"), anyString(), anyString())).thenReturn(
                new QualityEvaluationStrategy.EvaluationResult(false, 94, "模型A正常", List.of())
        );
        when(modelEvaluator.evaluate(eq("model-b"), anyString(), anyString()))
                .thenThrow(new RuntimeException("502 upstream"));

        CrossReview review = service.createAndExecute(
                "spec-1", "task-6", "profile-6", "deviation",
                List.of("model-a", "model-b"), "manual", null, "待审查内容", null, "tenant-1"
        );

        assertThat(review).isNotNull();
        assertThat(review.getStatus()).isEqualTo("succeeded");
        assertThat(review.getErrorMessage()).isNull();
        assertThat(review.getSummary()).containsEntry("modelCount", 2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) review.getMergedResult().get("findings");
        assertThat(findings)
                .extracting(item -> item.get("ruleCode"))
                .contains("qa.infrastructure.model_evaluation_failed");
        assertThat(review.getMergedResult()).containsEntry("hasIssue", true);
    }
}
