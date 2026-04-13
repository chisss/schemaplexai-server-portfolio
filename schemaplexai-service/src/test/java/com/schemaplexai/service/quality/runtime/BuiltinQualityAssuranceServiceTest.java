package com.schemaplexai.service.quality.runtime;

import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.IntentDefectMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategyRegistry;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicy;
import com.schemaplexai.service.quality.strategy.QualityReviewPolicyService;
import com.schemaplexai.service.quality.task.QualityTaskManager;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuiltinQualityAssuranceServiceTest {

    private final SpecMapper specMapper = mock(SpecMapper.class);
    private final SpecDocumentMapper specDocumentMapper = mock(SpecDocumentMapper.class);
    private final AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
    private final QualityDeviationMapper qualityDeviationMapper = mock(QualityDeviationMapper.class);
    private final IntentDefectMapper intentDefectMapper = mock(IntentDefectMapper.class);
    private final AgentConfigMapper agentConfigMapper = mock(AgentConfigMapper.class);
    private final QualityTaskManager qualityTaskManager = mock(QualityTaskManager.class);
    private final QualityProfileResolverService qualityProfileResolverService = mock(QualityProfileResolverService.class);
    private final QualityCrossReviewExecutionService crossReviewExecutionService = mock(QualityCrossReviewExecutionService.class);
    private final QualityEvaluationStrategyRegistry strategyRegistry = mock(QualityEvaluationStrategyRegistry.class);
    private final QualityReviewPolicyService reviewPolicyService = mock(QualityReviewPolicyService.class);

    private final BuiltinQualityAssuranceService service = new BuiltinQualityAssuranceService(
            specMapper, specDocumentMapper, agentExecutionMapper, qualityDeviationMapper, intentDefectMapper,
            agentConfigMapper, qualityTaskManager, qualityProfileResolverService, crossReviewExecutionService,
            strategyRegistry, reviewPolicyService
    );

    @Test
    void shouldPauseWorkflowWhenQualityGateConfiguredToPauseAndThresholdExceeded() {
        BuiltinQualityAssuranceService.WorkflowNodeQualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 62,
                        "qualityDeviationCount", 3,
                        "qualityWarningCount", 2
                ),
                Map.of(
                        "qualityGateMode", "pause",
                        "minQualityScore", 80,
                        "maxDeviationCount", 1
                )
        );

        assertThat(decision.pauseWorkflow()).isTrue();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.message()).contains("质量分低于阈值");
        assertThat(decision.message()).contains("偏离数量超限");
        assertThat(decision.toOutputData()).containsEntry("qualityGateDecision", "pause");
    }

    @Test
    void shouldWarnByDefaultWhenQualityGateThresholdExceeded() {
        BuiltinQualityAssuranceService.WorkflowNodeQualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 70,
                        "qualityDeviationCount", 2,
                        "qualityWarningCount", 2
                ),
                Map.of(
                        "minQualityScore", 85,
                        "maxWarningCount", 1
                )
        );

        assertThat(decision.pauseWorkflow()).isFalse();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.decision()).isEqualTo("warn");
    }

    @Test
    void shouldKeepDeviationOpenForManualDetection() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(specDocumentMapper.selectList(any())).thenReturn(List.of());

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-1");
        profile.setCode("QP-1");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "deviation", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-1")).thenReturn(List.of("model-1"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("deviation"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("deviation", "manual"));
        when(strategyRegistry.getStrategy("deviation")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "deviation";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return new EvaluationResult(true, 70, "发现偏离", List.of(
                        new Finding("omission", "warning", 90, "RULE-DEV-1",
                                "缺少验收标准", "输出未覆盖验收标准", "output#1", "补充验收标准")
                ));
            }
        });
        doNothing().when(qualityTaskManager).markRunning("task-1");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-1"), anyInt(), anyInt(), anyInt(), any(Map.class));

        service.handleDeviationMessage("task-1", "spec-1", "exec-1", "当前输出");

        ArgumentCaptor<QualityDeviation> captor = ArgumentCaptor.forClass(QualityDeviation.class);
        verify(qualityDeviationMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("open");
        assertThat(captor.getValue().getSourceType()).isEqualTo("manual");
        assertThat(captor.getValue().getRuleCode()).isEqualTo("RULE-DEV-1");
    }

    @Test
    void shouldPreserveIntentSourceTypeAndRuleCode() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        SpecDocument document = new SpecDocument();
        document.setContent("需求文档内容");
        when(specDocumentMapper.selectOne(any())).thenReturn(document);

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-2");
        profile.setCode("QP-2");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "intent_defect", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-2")).thenReturn(List.of("model-1"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("intent_defect"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("intent_defect", "manual"));
        when(strategyRegistry.getStrategy("intent_defect")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "intent_defect";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return new EvaluationResult(true, 66, "发现意图缺陷", List.of(
                        new Finding("ambiguity", "warning", 87, "RULE-INT-1",
                                "性能目标模糊", "未给出量化指标", "requirements#performance", "补充目标值")
                ));
            }
        });
        doNothing().when(qualityTaskManager).markRunning("task-2");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-2"), anyInt(), anyInt(), anyInt(), any(Map.class));

        service.handleIntentMessage("task-2", "spec-1", "requirements");

        ArgumentCaptor<IntentDefect> captor = ArgumentCaptor.forClass(IntentDefect.class);
        verify(intentDefectMapper).insert(captor.capture());
        assertThat(captor.getValue().getSourceType()).isEqualTo("manual");
        assertThat(captor.getValue().getRuleCode()).isEqualTo("RULE-INT-1");
    }

    @Test
    void shouldKeepDeviationSucceededWhenCrossReviewFails() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(specDocumentMapper.selectList(any())).thenReturn(List.of());

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-1");
        profile.setCode("QP-1");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "deviation", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-1")).thenReturn(List.of("model-1", "model-2"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("deviation"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("deviation", "manual"));
        when(strategyRegistry.getStrategy("deviation")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "deviation";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return new EvaluationResult(true, 82, "发现偏离", List.of(
                        new Finding("omission", "warning", 90, "RULE-DEV-2",
                                "缺少回滚策略", "输出未覆盖回滚预案", "output#rollback", "补充回滚方案")
                ));
            }
        });
        doNothing().when(qualityTaskManager).markRunning("task-3");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-3"), anyInt(), anyInt(), anyInt(), any(Map.class));
        when(crossReviewExecutionService.createAndExecute(
                eq("spec-1"), eq("task-3"), eq("profile-1"), eq("deviation"),
                eq(List.of("model-1", "model-2")), eq("manual"), any(), eq("当前输出"), eq("tenant-1")
        )).thenThrow(new RuntimeException("cross review unavailable"));

        Map<String, Object> result = service.handleDeviationMessage("task-3", "spec-1", "exec-1", "当前输出");

        assertThat(result).containsEntry("qualityTaskStatus", "succeeded");
        assertThat(result).containsEntry("crossReviewStatus", "failed");
        verify(qualityTaskManager, never()).markFailed(eq("task-3"), anyString());
    }

    @Test
    void shouldMergeCrossReviewFindingsIntoDeviationSummaryAndPersistence() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(specDocumentMapper.selectList(any())).thenReturn(List.of());

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-1");
        profile.setCode("QP-1");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "deviation", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-1")).thenReturn(List.of("model-1", "model-2"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("deviation"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("deviation", "manual"));
        when(strategyRegistry.getStrategy("deviation")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "deviation";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return EvaluationResult.empty();
            }
        });
        CrossReview crossReview = new CrossReview();
        crossReview.setId("cross-1");
        crossReview.setStatus("succeeded");
        crossReview.setSummary(Map.of("overview", "交叉审核发现 1 个关键问题"));
        crossReview.setMergedResult(Map.of(
                "score", 81,
                "findings", List.of(Map.of(
                        "type", "omission",
                        "severity", "warning",
                        "confidence", 88,
                        "ruleCode", "RULE-CR-1",
                        "title", "缺少回滚策略",
                        "description", "交叉审核识别出回滚预案缺失",
                        "location", "design#rollback",
                        "suggestion", "补充回滚方案"
                ))
        ));
        when(crossReviewExecutionService.createAndExecute(
                eq("spec-1"), eq("task-4"), eq("profile-1"), eq("deviation"),
                eq(List.of("model-1", "model-2")), eq("manual"), any(), eq("当前输出"), eq("tenant-1")
        )).thenReturn(crossReview);
        when(qualityTaskManager.createTask(
                eq("task-4"), eq("deviation"), eq("agent_execution"), eq("manual"),
                any(), eq("tenant-1"), eq("spec-1"), eq("wf-1"), eq("exec-1"),
                eq("profile-1"), eq("QP-1"), any(Map.class)
        )).thenReturn("task-4");
        doNothing().when(qualityTaskManager).markRunning("task-4");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-4"), anyInt(), anyInt(), anyInt(), any(Map.class));

        Map<String, Object> result = service.handleDeviationMessage("task-4", "spec-1", "exec-1", "当前输出");

        ArgumentCaptor<QualityDeviation> deviationCaptor = ArgumentCaptor.forClass(QualityDeviation.class);
        verify(qualityDeviationMapper).insert(deviationCaptor.capture());
        assertThat(deviationCaptor.getValue().getRuleCode()).isEqualTo("RULE-CR-1");
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(qualityTaskManager).markSucceeded(eq("task-4"), eq(1), eq(1), eq(0), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue()).containsEntry("score", 81);
        assertThat(summaryCaptor.getValue()).containsEntry("deviationCount", 1);
        assertThat(summaryCaptor.getValue()).containsEntry("crossReviewFindingCount", 1);
        assertThat(result).containsEntry("qualityScore", 81);
        assertThat(result).containsEntry("qualityDeviationCount", 1);
        assertThat(result).containsEntry("crossReviewId", "cross-1");
    }

    @Test
    void shouldMergeCrossReviewFindingsIntoIntentSummaryAndPersistence() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        SpecDocument document = new SpecDocument();
        document.setContent("需求文档内容");
        when(specDocumentMapper.selectOne(any())).thenReturn(document);

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-2");
        profile.setCode("QP-2");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "intent_defect", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-2")).thenReturn(List.of("model-1", "model-2"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("intent_defect"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("intent_defect", "manual"));
        when(strategyRegistry.getStrategy("intent_defect")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "intent_defect";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return EvaluationResult.empty();
            }
        });
        CrossReview crossReview = new CrossReview();
        crossReview.setId("cross-2");
        crossReview.setStatus("succeeded");
        crossReview.setSummary(Map.of("overview", "交叉审核发现 1 个意图缺陷"));
        crossReview.setMergedResult(Map.of(
                "score", 76,
                "findings", List.of(Map.of(
                        "type", "ambiguity",
                        "severity", "warning",
                        "confidence", 91,
                        "ruleCode", "RULE-CR-INT-1",
                        "title", "性能指标模糊",
                        "description", "交叉审核识别出性能目标缺少量化指标",
                        "location", "requirements#performance",
                        "suggestion", "补充明确阈值"
                ))
        ));
        when(crossReviewExecutionService.createAndExecute(
                eq("spec-1"), eq("task-5"), eq("profile-2"), eq("intent_defect"),
                eq(List.of("model-1", "model-2")), eq("manual"), any(), eq("需求文档内容"), eq("tenant-1")
        )).thenReturn(crossReview);
        when(qualityTaskManager.createTask(
                eq("task-5"), eq("intent_defect"), eq("event"), eq("manual"),
                any(), eq("tenant-1"), eq("spec-1"), eq("wf-1"), eq(null),
                eq("profile-2"), eq("QP-2"), any(Map.class)
        )).thenReturn("task-5");
        doNothing().when(qualityTaskManager).markRunning("task-5");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-5"), anyInt(), anyInt(), anyInt(), any(Map.class));

        Map<String, Object> result = service.handleIntentMessage("task-5", "spec-1", "requirements");

        ArgumentCaptor<IntentDefect> defectCaptor = ArgumentCaptor.forClass(IntentDefect.class);
        verify(intentDefectMapper).insert(defectCaptor.capture());
        assertThat(defectCaptor.getValue().getRuleCode()).isEqualTo("RULE-CR-INT-1");
        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(qualityTaskManager).markSucceeded(eq("task-5"), eq(1), eq(1), eq(0), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue()).containsEntry("score", 76);
        assertThat(summaryCaptor.getValue()).containsEntry("defectCount", 1);
        assertThat(summaryCaptor.getValue()).containsEntry("crossReviewFindingCount", 1);
        assertThat(result).containsEntry("qualityScore", 76);
        assertThat(result).containsEntry("qualityDefectCount", 1);
        assertThat(result).containsEntry("crossReviewId", "cross-2");
    }

    @Test
    void shouldUseMatchingCrossReviewReviewerResultWhenPrimaryEvaluationIsInfrastructureFailure() {
        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setTenantId("tenant-1");
        spec.setWorkflowId("wf-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        SpecDocument document = new SpecDocument();
        document.setContent("需求文档内容");
        when(specDocumentMapper.selectOne(any())).thenReturn(document);

        QualityProfile profile = new QualityProfile();
        profile.setId("profile-3");
        profile.setCode("QP-3");
        when(qualityProfileResolverService.resolveProfile("spec-1", "wf-1", null, "intent_defect", "manual")).thenReturn(profile);
        when(qualityProfileResolverService.listModelIds("profile-3")).thenReturn(List.of("model-1", "model-2"));
        when(agentConfigMapper.selectList(any())).thenReturn(List.of());
        when(reviewPolicyService.resolvePolicy(eq(profile), eq("intent_defect"), eq("manual")))
                .thenReturn(QualityReviewPolicy.empty("intent_defect", "manual"));
        when(strategyRegistry.getStrategy("intent_defect")).thenReturn(new QualityEvaluationStrategy() {
            @Override
            public String supportedIssueType() {
                return "intent_defect";
            }

            @Override
            public EvaluationResult evaluate(EvaluationContext context) {
                return new EvaluationResult(true, 20, "质量评估结果解析失败", List.of(
                        new Finding("omission", "critical", 100, "qa.infrastructure.model_response_invalid",
                                "质量评估结果解析失败", "模型返回内容不是可解析的 JSON", "", "检查模型配置")
                ));
            }
        });
        CrossReview crossReview = new CrossReview();
        crossReview.setId("cross-3");
        crossReview.setStatus("succeeded");
        crossReview.setModelAId("model-1");
        crossReview.setModelAResult(Map.of(
                "modelId", "model-1",
                "hasIssue", true,
                "score", 86,
                "summary", "主模型在交叉审核中输出正常",
                "findings", List.of(Map.of(
                        "type", "omission",
                        "severity", "warning",
                        "confidence", 89,
                        "ruleCode", "RULE-INT-RECOVERED",
                        "title", "缺少重试机制",
                        "description", "交叉审核中的同模型输出识别出补偿机制缺失",
                        "location", "design#retry",
                        "suggestion", "补充重试补偿设计"
                ))
        ));
        crossReview.setMergedResult(Map.of(
                "score", 76,
                "findings", List.of(Map.of(
                        "type", "omission",
                        "severity", "warning",
                        "confidence", 89,
                        "ruleCode", "RULE-INT-RECOVERED",
                        "title", "缺少重试机制",
                        "description", "交叉审核中的同模型输出识别出补偿机制缺失",
                        "location", "design#retry",
                        "suggestion", "补充重试补偿设计"
                ))
        ));
        crossReview.setSummary(Map.of("overview", "交叉审核恢复主模型结果"));
        when(crossReviewExecutionService.createAndExecute(
                eq("spec-1"), eq("task-6"), eq("profile-3"), eq("intent_defect"),
                eq(List.of("model-1", "model-2")), eq("manual"), any(), eq("需求文档内容"), eq("tenant-1")
        )).thenReturn(crossReview);
        when(qualityTaskManager.createTask(
                eq("task-6"), eq("intent_defect"), eq("event"), eq("manual"),
                any(), eq("tenant-1"), eq("spec-1"), eq("wf-1"), eq(null),
                eq("profile-3"), eq("QP-3"), any(Map.class)
        )).thenReturn("task-6");
        doNothing().when(qualityTaskManager).markRunning("task-6");
        doNothing().when(qualityTaskManager).markSucceeded(eq("task-6"), anyInt(), anyInt(), anyInt(), any(Map.class));

        Map<String, Object> result = service.handleIntentMessage("task-6", "spec-1", "requirements");

        ArgumentCaptor<Map<String, Object>> summaryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(qualityTaskManager).markSucceeded(eq("task-6"), eq(1), eq(1), eq(0), summaryCaptor.capture());
        assertThat(summaryCaptor.getValue()).containsEntry("score", 76);
        assertThat(summaryCaptor.getValue()).containsEntry("crossReviewScore", 76);
        assertThat(result).containsEntry("qualityScore", 76);

        ArgumentCaptor<IntentDefect> defectCaptor = ArgumentCaptor.forClass(IntentDefect.class);
        verify(intentDefectMapper).insert(defectCaptor.capture());
        assertThat(defectCaptor.getValue().getRuleCode()).isEqualTo("RULE-INT-RECOVERED");
    }
}
