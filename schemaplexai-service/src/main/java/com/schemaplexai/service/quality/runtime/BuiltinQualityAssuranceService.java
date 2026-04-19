package com.schemaplexai.service.quality.runtime;

import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.quality.gate.QualityGateContext;
import com.schemaplexai.service.quality.gate.QualityGateDecision;
import com.schemaplexai.service.quality.gate.QualityGateEvaluator;
import com.schemaplexai.service.quality.pipeline.QualityCheckRequest;
import com.schemaplexai.service.quality.pipeline.QualityCheckResult;
import com.schemaplexai.service.quality.pipeline.QualityExecutionPipeline;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质量保障服务 Facade
 * 委托 QualityExecutionPipeline 执行三层检查，委托 QualityGateEvaluator 评估闸门决策。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinQualityAssuranceService {

    private static final int IMPLEMENTATION_GUARD_SCORE = 35;

    private final QualityExecutionPipeline qualityExecutionPipeline;
    private final QualityGateEvaluator qualityGateEvaluator;
    private final GitOperationService gitOperationService;

    /**
     * 工作流 Agent 节点质量检查（SHORT_WAIT 策略）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeAgentNode(WorkflowInstance instance,
                                                WorkflowNodeExecution nodeExec,
                                                String result) {
        return analyzeAgentNode(instance, nodeExec, Map.of(), result);
    }

    /**
     * 工作流 Agent 节点质量检查（支持节点级配置）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeAgentNode(WorkflowInstance instance,
                                                WorkflowNodeExecution nodeExec,
                                                Map<String, Object> nodeConfig,
                                                String result) {
        if (instance == null || nodeExec == null || !StringUtils.hasText(instance.getSpecId())) {
            return Map.of();
        }
        Map<String, Object> safeNodeConfig = nodeConfig == null ? Map.of() : nodeConfig;
        QualityCheckRequest request = new QualityCheckRequest(
                instance.getSpecId(), result, null,
                QualityIssueTypeEnum.DEVIATION.getCode(), "workflow",
                resolveExecutionStrategy(safeNodeConfig),
                readString(safeNodeConfig, "qualityProfileId"),
                safeNodeConfig,
                instance.getTemplateId(), nodeExec.getAgentExecutionId(),
                nodeExec.getNodeId(), nodeExec.getNodeLabel(),
                readString(safeNodeConfig, "artifactDocType"),
                instance.getTenantId(), 0
        );
        QualityCheckResult checkResult = qualityExecutionPipeline.execute(request);
        Map<String, Object> qualityOutput = new LinkedHashMap<>(checkResult.toOutputMap());
        return applyImplementationEvidenceGuard(instance, safeNodeConfig, qualityOutput);
    }

    /**
     * MQ 偏离检测消息处理（ASYNC 策略）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleDeviationMessage(String taskId, String specId, String agentExecutionId, String targetContent) {
        QualityCheckRequest request = new QualityCheckRequest(
                specId, targetContent, null,
                QualityIssueTypeEnum.DEVIATION.getCode(), "manual",
                ExecutionStrategyEnum.ASYNC.getCode(), null, Map.of(),
                null, agentExecutionId, "manual_quality_check", "质量保障中心偏离检测",
                null, null, 0
        );
        QualityCheckResult checkResult = qualityExecutionPipeline.execute(request);
        return checkResult.toOutputMap();
    }

    /**
     * 意图缺陷分析（ASYNC 策略）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> analyzeIntentDefects(String specId, String docType) {
        QualityCheckRequest request = QualityCheckRequest.forIntentDefect(specId, null, docType, null, null);
        QualityCheckResult checkResult = qualityExecutionPipeline.execute(request);
        return checkResult.toOutputMap();
    }

    /**
     * MQ 意图缺陷消息处理（ASYNC 策略）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> handleIntentMessage(String taskId, String specId, String docType) {
        QualityCheckRequest request = QualityCheckRequest.forIntentDefect(specId, null, docType, null, null);
        QualityCheckResult checkResult = qualityExecutionPipeline.execute(request);
        return checkResult.toOutputMap();
    }

    /**
     * 评估工作流节点质量闸门
     * 保持原有方法签名，内部委托给 QualityGateEvaluator
     */
    public QualityGateDecision evaluateWorkflowNodeGate(Map<String, Object> qualityResult,
                                                        Map<String, Object> nodeConfig) {
        QualityGateContext ctx = QualityGateContext.of(qualityResult, nodeConfig);
        return qualityGateEvaluator.evaluate(ctx);
    }

    private Map<String, Object> applyImplementationEvidenceGuard(WorkflowInstance instance,
                                                                 Map<String, Object> nodeConfig,
                                                                 Map<String, Object> qualityOutput) {
        if (!isImplementationStage(nodeConfig)) {
            return qualityOutput;
        }
        String workspacePath = readString(instance != null ? instance.getVariables() : Map.of(), "workspacePath");
        if (!StringUtils.hasText(workspacePath)) {
            qualityOutput.put("implementationEvidenceChecked", false);
            qualityOutput.put("implementationEvidencePassed", false);
            qualityOutput.put("implementationEvidenceMessage", "代码开发节点未提供工作区路径，无法校验真实实现改动");
            return qualityOutput;
        }
        try {
            GitOperationService.WorkspaceChangeSummary summary = gitOperationService.inspectWorkspaceChanges(workspacePath);
            qualityOutput.put("implementationEvidenceChecked", true);
            qualityOutput.put("implementationEvidenceChangeCount", summary.totalChangedFiles());
            qualityOutput.put("implementationEvidenceImplementationChangeCount", summary.implementationChangedFileCount());
            qualityOutput.put("implementationEvidenceChangedFiles", summary.changedFiles());
            qualityOutput.put("implementationEvidenceImplementationFiles", summary.implementationFiles());
            if (summary.implementationChangedFileCount() > 0) {
                qualityOutput.put("implementationEvidencePassed", true);
                qualityOutput.put("implementationEvidenceMessage", "已检测到真实实现类改动");
                return qualityOutput;
            }
            String guardMessage = buildImplementationGuardMessage(summary);
            qualityOutput.put("implementationEvidencePassed", false);
            qualityOutput.put("implementationEvidenceMessage", guardMessage);
            qualityOutput.put("qualityScore", Math.min(readInteger(qualityOutput.get("qualityScore"), 100), IMPLEMENTATION_GUARD_SCORE));
            qualityOutput.put("qualitySummary", mergeQualitySummary(readString(qualityOutput, "qualitySummary"), guardMessage));
            qualityOutput.put("qualityWarningCount", Math.max(readInteger(qualityOutput.get("qualityWarningCount"), 0), 1));
            qualityOutput.put("qualityDeviationCount", Math.max(readInteger(qualityOutput.get("qualityDeviationCount"), 0), 1));
            return qualityOutput;
        } catch (IOException ex) {
            log.warn("实现证据校验失败: specId={}, nodeId={}, workspacePath={}, error={}",
                    instance != null ? instance.getSpecId() : null,
                    nodeConfig.getOrDefault("nodeId", null),
                    workspacePath,
                    ex.getMessage());
            qualityOutput.put("implementationEvidenceChecked", false);
            qualityOutput.put("implementationEvidencePassed", false);
            qualityOutput.put("implementationEvidenceMessage", "实现证据校验失败: " + ex.getMessage());
            return qualityOutput;
        }
    }

    private boolean isImplementationStage(Map<String, Object> nodeConfig) {
        String artifactDocType = readString(nodeConfig, "artifactDocType");
        String outputVariableKey = readString(nodeConfig, "outputVariableKey");
        return "implementation".equals(artifactDocType)
                || "codeDevelopmentSummary".equals(outputVariableKey)
                || "implementationDoc".equals(outputVariableKey);
    }

    private String resolveExecutionStrategy(Map<String, Object> nodeConfig) {
        String executionStrategy = readString(nodeConfig, "executionStrategy");
        return StringUtils.hasText(executionStrategy)
                ? executionStrategy
                : ExecutionStrategyEnum.SHORT_WAIT.getCode();
    }

    private String buildImplementationGuardMessage(GitOperationService.WorkspaceChangeSummary summary) {
        if (summary == null || summary.totalChangedFiles() == 0) {
            return "代码开发节点未检测到任何真实工作区改动，当前输出不能视为已完成实现交付";
        }
        ArrayList<String> previewFiles = new ArrayList<>(summary.changedFiles().stream().limit(5).toList());
        return "代码开发节点未检测到代码/配置/SQL等实现类改动，仅发现非实现类改动: " + String.join(", ", previewFiles);
    }

    private String mergeQualitySummary(String originalSummary, String guardMessage) {
        if (!StringUtils.hasText(originalSummary)) {
            return guardMessage;
        }
        if (originalSummary.contains(guardMessage)) {
            return originalSummary;
        }
        return originalSummary + "；" + guardMessage;
    }

    private String readString(Map<String, Object> source, String key) {
        if (source == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private int readInteger(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
