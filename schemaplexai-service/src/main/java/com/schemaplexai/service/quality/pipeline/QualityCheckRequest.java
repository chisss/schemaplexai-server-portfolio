package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.QualityIssueTypeEnum;

import java.util.Map;

/**
 * 质量检查请求
 */
public record QualityCheckRequest(
        String specId,
        String targetContent,
        String referenceContent,
        String issueType,
        String sourceType,
        /** 执行策略: sync / short_wait / async */
        String executionStrategy,
        String profileId,
        Map<String, Object> nodeConfig,
        String workflowTemplateId,
        String agentExecutionId,
        String nodeId,
        String nodeLabel,
        String docType,
        String tenantId,
        int retryCount
) {

    public QualityCheckRequest {
        executionStrategy = executionStrategy == null ? ExecutionStrategyEnum.SHORT_WAIT.getCode() : executionStrategy;
        nodeConfig = nodeConfig == null ? Map.of() : nodeConfig;
        retryCount = Math.max(0, retryCount);
    }

    /** 快捷构造：工作流场景 */
    public static QualityCheckRequest forWorkflow(String specId, String targetContent,
                                                   String workflowTemplateId, String agentExecutionId,
                                                   String nodeId, String nodeLabel,
                                                   String profileId, String tenantId,
                                                   Map<String, Object> nodeConfig) {
        return new QualityCheckRequest(
                specId, targetContent, null,
                QualityIssueTypeEnum.DEVIATION.getCode(), "workflow",
                ExecutionStrategyEnum.SHORT_WAIT.getCode(), profileId, nodeConfig,
                workflowTemplateId, agentExecutionId, nodeId, nodeLabel,
                null, tenantId, 0
        );
    }

    /** 快捷构造：手动触发 */
    public static QualityCheckRequest forManual(String specId, String targetContent,
                                                 String issueType, String profileId,
                                                 String tenantId) {
        return new QualityCheckRequest(
                specId, targetContent, null, issueType, "manual",
                ExecutionStrategyEnum.ASYNC.getCode(), profileId, Map.of(),
                null, null, null, null,
                null, tenantId, 0
        );
    }

    /** 快捷构造：意图缺陷分析 */
    public static QualityCheckRequest forIntentDefect(String specId, String content,
                                                       String docType, String profileId,
                                                       String tenantId) {
        return new QualityCheckRequest(
                specId, content, null,
                QualityIssueTypeEnum.INTENT_DEFECT.getCode(), "system",
                ExecutionStrategyEnum.ASYNC.getCode(), profileId, Map.of(),
                null, null, null, null,
                docType, tenantId, 0
        );
    }
}
