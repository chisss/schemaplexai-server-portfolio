package com.schemaplexai.service.quality.gate;

import com.schemaplexai.common.enums.ExecutionStrategyEnum;

import java.util.Map;

/**
 * 质量闸门评估上下文
 */
public record QualityGateContext(
        /* 质量检查结果（来自 QualityExecutionPipeline） */
        Map<String, Object> qualityResult,
        /* 工作流节点配置 */
        Map<String, Object> nodeConfig,
        /* QualityProfile 配置 */
        Map<String, Object> profileConfig,
        /* 执行策略: sync / short_wait / async */
        String executionStrategy,
        /* 当前重试次数 */
        int retryCount,
        /* 最大重试次数 */
        int maxRetryCount
) {

    public QualityGateContext {
        qualityResult = qualityResult == null ? Map.of() : qualityResult;
        nodeConfig = nodeConfig == null ? Map.of() : nodeConfig;
        profileConfig = profileConfig == null ? Map.of() : profileConfig;
        executionStrategy = executionStrategy == null ? ExecutionStrategyEnum.SHORT_WAIT.getCode() : executionStrategy;
        retryCount = Math.max(0, retryCount);
        maxRetryCount = Math.max(0, maxRetryCount);
    }

    /** 便捷构造：最小化参数 */
    public static QualityGateContext of(Map<String, Object> qualityResult, Map<String, Object> nodeConfig) {
        return new QualityGateContext(qualityResult, nodeConfig, Map.of(), ExecutionStrategyEnum.SHORT_WAIT.getCode(), 0, 0);
    }
}
