package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 质量检查结果
 */
public record QualityCheckResult(
        /** 质量任务ID */
        String taskId,
        /** 闸门决策: pass / warn / pause / fail / retry */
        String gateDecision,
        /** 发现的质量问题列表 */
        List<QualityEvaluationStrategy.Finding> findings,
        /** 质量分数 0-100 */
        int score,
        /** 摘要信息 */
        String summary,
        /** 交叉审查ID（如触发） */
        String crossReviewId,
        /** 执行耗时（毫秒） */
        long executionDurationMs,
        /** 执行策略 */
        String executionStrategy,
        /** 任务状态 */
        String taskStatus,
        /** 偏离数量 */
        int deviationCount,
        /** 预警数量 */
        int warningCount,
        /** 必需检查维度、规则和模型评估的覆盖状态 */
        Map<String, Object> coverage
) {

    public QualityCheckResult {
        coverage = coverage == null ? Map.of() : Map.copyOf(coverage);
    }

    /** 兼容旧调用方；未传 coverage 时按任务状态生成最小可审计覆盖。 */
    public QualityCheckResult(String taskId, String gateDecision,
                              List<QualityEvaluationStrategy.Finding> findings, int score, String summary,
                              String crossReviewId, long executionDurationMs, String executionStrategy,
                              String taskStatus, int deviationCount, int warningCount) {
        this(taskId, gateDecision, findings, score, summary, crossReviewId, executionDurationMs,
                executionStrategy, taskStatus, deviationCount, warningCount,
                legacyCoverage(taskStatus));
    }

    /** 转换为 Map（兼容原有返回格式） */
    public Map<String, Object> toOutputMap() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("qualityTaskId", taskId);
        output.put("qualityTaskStatus", taskStatus);
        output.put("qualityGateDecision", gateDecision);
        output.put("qualityScore", score);
        output.put("qualitySummary", summary);
        output.put("qualityDeviationCount", deviationCount);
        output.put("qualityWarningCount", warningCount);
        output.put("qualityCheckedAt", java.time.LocalDateTime.now().toString());
        output.put("qualityExecutionStrategy", executionStrategy);
        output.put("qualityCoverage", coverage);
        output.put("qualityCoverageComplete", coverageComplete());
        if (crossReviewId != null) {
            output.put("crossReviewId", crossReviewId);
        }
        return output;
    }

    public boolean coverageComplete() {
        return !Boolean.TRUE.equals(coverage.get("requiredIncomplete"))
                && !"error".equalsIgnoreCase(String.valueOf(coverage.get("status")));
    }

    /** 空结果 */
    public static QualityCheckResult empty(String taskId) {
        return new QualityCheckResult(
                taskId, GateDecisionEnum.ERROR.getCode(), List.of(), 0,
                "未获得质量检查结果", null, 0, ExecutionStrategyEnum.SYNC.getCode(),
                TaskStatusEnum.FAILED.getCode(), 0, 0,
                Map.of("status", "error", "requiredIncomplete", true,
                        "dimensions", Map.of(), "rules", Map.of(),
                        "model", Map.of("status", "unknown", "required", false, "reason", "no_result"))
        );
    }

    private static Map<String, Object> legacyCoverage(String taskStatus) {
        boolean failed = TaskStatusEnum.FAILED.getCode().equalsIgnoreCase(taskStatus);
        return Map.of(
                "status", failed ? "error" : "complete",
                "requiredIncomplete", failed,
                "dimensions", Map.of(),
                "rules", Map.of(),
                "model", Map.of("status", "unknown", "required", false, "reason", "legacy_result")
        );
    }
}
