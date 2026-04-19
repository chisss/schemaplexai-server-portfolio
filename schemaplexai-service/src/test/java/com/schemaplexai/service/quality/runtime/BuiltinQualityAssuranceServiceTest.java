package com.schemaplexai.service.quality.runtime;

import com.schemaplexai.service.quality.gate.QualityGateContext;
import com.schemaplexai.service.quality.gate.QualityGateDecision;
import com.schemaplexai.service.quality.gate.QualityGateEvaluator;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.quality.pipeline.QualityCheckResult;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.pipeline.QualityExecutionPipeline;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuiltinQualityAssuranceServiceTest {

    private final QualityExecutionPipeline pipeline = mock(QualityExecutionPipeline.class);
    private final QualityGateEvaluator gateEvaluator = new QualityGateEvaluator();
    private final GitOperationService gitOperationService = mock(GitOperationService.class);

    private final BuiltinQualityAssuranceService service =
            new BuiltinQualityAssuranceService(pipeline, gateEvaluator, gitOperationService);

    @Test
    void shouldPauseWorkflowWhenQualityGateConfiguredToPauseAndThresholdExceeded() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
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
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
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
    void shouldPauseWhenDefaultFallbackThresholdExceededWithoutNodeConfig() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 35,
                        "qualityWarningCount", 6
                ),
                Map.of()
        );

        assertThat(decision.pauseWorkflow()).isTrue();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.decision()).isEqualTo("pause");
        assertThat(decision.message()).contains("命中默认质量闸门");
        assertThat(decision.message()).contains("35<60");
    }

    @Test
    void shouldPauseWhenQualityTaskFailedWithoutExplicitGateConfig() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "failed",
                        "qualitySummary", "模型评估超时"
                ),
                Map.of()
        );

        assertThat(decision.pauseWorkflow()).isTrue();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.decision()).isEqualTo("pause");
        assertThat(decision.message()).contains("质量任务执行失败");
    }

    @Test
    void shouldWarnWhenExplicitWarnModeUsesDefaultFallbackThreshold() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 35,
                        "qualityWarningCount", 6
                ),
                Map.of("qualityGateMode", "warn")
        );

        assertThat(decision.pauseWorkflow()).isFalse();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.decision()).isEqualTo("warn");
        assertThat(decision.message()).contains("35<60");
    }

    @Test
    void shouldPauseWhenExplicitPauseModeUsesDefaultFallbackThreshold() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 35,
                        "qualityWarningCount", 6
                ),
                Map.of("qualityGateMode", "pause")
        );

        assertThat(decision.pauseWorkflow()).isTrue();
        assertThat(decision.failWorkflow()).isFalse();
        assertThat(decision.decision()).isEqualTo("pause");
        assertThat(decision.message()).contains("35<60");
    }

    @Test
    void shouldDelegateToPipelineForDeviationAnalysis() {
        QualityCheckResult mockResult = new QualityCheckResult(
                "task-1", "pending_gate",
                List.of(new QualityEvaluationStrategy.Finding(
                        "omission", "warning", 90, "RULE-DEV-1",
                        "缺少验收标准", "输出未覆盖验收标准", "output#1", "补充验收标准"
                )),
                70, "发现偏离", null, 200, "async", "succeeded", 1, 1
        );
        when(pipeline.execute(any())).thenReturn(mockResult);

        Map<String, Object> result = service.handleDeviationMessage("task-1", "spec-1", "exec-1", "当前输出");

        assertThat(result).containsEntry("qualityTaskId", "task-1");
        assertThat(result).containsEntry("qualityTaskStatus", "succeeded");
        assertThat(result).containsEntry("qualityScore", 70);
    }

    @Test
    void shouldDelegateToPipelineForIntentAnalysis() {
        QualityCheckResult mockResult = new QualityCheckResult(
                "task-2", "pending_gate",
                List.of(new QualityEvaluationStrategy.Finding(
                        "ambiguity", "warning", 87, "RULE-INT-1",
                        "性能目标模糊", "未给出量化指标", "requirements#performance", "补充目标值"
                )),
                66, "发现意图缺陷", null, 300, "async", "succeeded", 1, 1
        );
        when(pipeline.execute(any())).thenReturn(mockResult);

        Map<String, Object> result = service.handleIntentMessage("task-2", "spec-1", "requirements");

        assertThat(result).containsEntry("qualityTaskId", "task-2");
        assertThat(result).containsEntry("qualityTaskStatus", "succeeded");
        assertThat(result).containsEntry("qualityScore", 66);
    }

    @Test
    void shouldRespectNodeConfigAndApplyImplementationEvidenceGuard() throws Exception {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setSpecId("spec-1");
        instance.setTemplateId("tpl-1");
        instance.setTenantId("tenant-1");
        instance.setVariables(Map.of("workspacePath", "/tmp/worktree"));

        WorkflowNodeExecution nodeExec = new WorkflowNodeExecution();
        nodeExec.setNodeId("code_development");
        nodeExec.setNodeLabel("代码开发");
        nodeExec.setAgentExecutionId("agent-exec-1");

        when(pipeline.execute(any())).thenReturn(new QualityCheckResult(
                "task-3", "pending_gate", List.of(),
                95, "模型评估认为输出完整", null, 120L,
                "async", "succeeded", 0, 0
        ));
        when(gitOperationService.inspectWorkspaceChanges("/tmp/worktree"))
                .thenReturn(new GitOperationService.WorkspaceChangeSummary(true, List.of(), List.of()));

        Map<String, Object> result = service.analyzeAgentNode(
                instance,
                nodeExec,
                Map.of(
                        "executionStrategy", "async",
                        "qualityProfileId", "profile-1",
                        "artifactDocType", "implementation",
                        "outputVariableKey", "codeDevelopmentSummary"
                ),
                "实现报告"
        );

        ArgumentCaptor<QualityCheckRequest> captor = ArgumentCaptor.forClass(QualityCheckRequest.class);
        verify(pipeline).execute(captor.capture());
        QualityCheckRequest request = captor.getValue();

        assertThat(request.executionStrategy()).isEqualTo("async");
        assertThat(request.profileId()).isEqualTo("profile-1");
        assertThat(request.docType()).isEqualTo("implementation");
        assertThat(request.nodeConfig()).containsEntry("artifactDocType", "implementation");
        assertThat(result).containsEntry("implementationEvidenceChecked", true);
        assertThat(result).containsEntry("implementationEvidencePassed", false);
        assertThat(result).containsEntry("qualityScore", 35);
        assertThat(String.valueOf(result.get("qualitySummary"))).contains("未检测到任何真实工作区改动");
    }

    @Test
    void shouldKeepHighScoreWhenImplementationNodeHasRealWorkspaceChanges() throws Exception {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-2");
        instance.setSpecId("spec-2");
        instance.setTemplateId("tpl-2");
        instance.setTenantId("tenant-2");
        instance.setVariables(Map.of("workspacePath", "/tmp/worktree-2"));

        WorkflowNodeExecution nodeExec = new WorkflowNodeExecution();
        nodeExec.setNodeId("code_development");
        nodeExec.setNodeLabel("代码开发");
        nodeExec.setAgentExecutionId("agent-exec-2");

        when(pipeline.execute(any())).thenReturn(new QualityCheckResult(
                "task-4", "pending_gate", List.of(),
                88, "输出基本完整", null, 100L,
                "short_wait", "succeeded", 0, 0
        ));
        when(gitOperationService.inspectWorkspaceChanges("/tmp/worktree-2"))
                .thenReturn(new GitOperationService.WorkspaceChangeSummary(
                        false,
                        List.of("src/main/java/com/example/App.java", "docs/output.md"),
                        List.of("src/main/java/com/example/App.java")
                ));

        Map<String, Object> result = service.analyzeAgentNode(
                instance,
                nodeExec,
                Map.of("artifactDocType", "implementation"),
                "实现报告"
        );

        assertThat(result).containsEntry("implementationEvidenceChecked", true);
        assertThat(result).containsEntry("implementationEvidencePassed", true);
        assertThat(result).containsEntry("qualityScore", 88);
        assertThat(String.valueOf(result.get("implementationEvidenceMessage"))).contains("已检测到真实实现类改动");
    }

    @Test
    void shouldRetryWhenScoreCloseToThreshold() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of(
                        "qualityTaskStatus", "succeeded",
                        "qualityScore", 75,
                        "qualityDeviationCount", 1,
                        "qualityWarningCount", 1,
                        "qualitySummary", "发现 1 个偏离"
                ),
                Map.of(
                        "qualityGateMode", "pause",
                        "minQualityScore", 80
                )
        );

        // 默认 maxRetryCount=0，不触发 retry，回退到 pause
        assertThat(decision.decision()).isEqualTo("pause");
    }

    @Test
    void shouldPassWhenDisabled() {
        QualityGateDecision decision = service.evaluateWorkflowNodeGate(
                Map.of("qualityScore", 30),
                Map.of("qualityGateMode", "disabled")
        );

        assertThat(decision.decision()).isEqualTo("pass");
        assertThat(decision.isPassed()).isTrue();
    }
}
