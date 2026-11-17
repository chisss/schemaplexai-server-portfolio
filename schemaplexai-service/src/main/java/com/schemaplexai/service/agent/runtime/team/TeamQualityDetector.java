package com.schemaplexai.service.agent.runtime.team;

import java.util.List;

/**
 * Team 质量判据与规则化兜底接口（审计 P2-1：可插拔 QualityDetector）。
 *
 * <p>状态语义：COMPLETED=全部成员成功；COMPLETED_WITH_FALLBACK=成员模型通道异常但规则化兜底
 * 已产出可复核交付；FAILED=失败或重试耗尽且无可用交付；PAUSED=等待审批/人工输入。</p>
 */
public interface TeamQualityDetector {

    boolean isSuccessfulOutputStatus(String status);

    boolean isTransientModelChannelFailure(String errorMessage);

    boolean shouldUseRuleBasedFallback(List<String> errors);

    String resolveThrowableMessage(Throwable throwable);

    String buildExecutionLevelFallbackOutput(String executionId, String agentId, String errorMessage);

    Integer resolveMinimumCompanyCount(String prompt);

    int countQualifiedCompanyRows(String markdown);

    int countDetectedCompanyRows(String markdown);

    boolean containsConclusionBlocker(String markdown);
}
