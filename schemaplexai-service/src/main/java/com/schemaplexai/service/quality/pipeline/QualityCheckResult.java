package com.schemaplexai.service.quality.pipeline;

import com.schemaplexai.common.enums.ExecutionStrategyEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.service.quality.strategy.QualityEvaluationStrategy;

import java.util.List;
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
        int warningCount
) {

    /** 转换为 Map（兼容原有返回格式） */
    public Map<String, Object> toOutputMap() {
        Map<String, Object> output = new java.util.LinkedHashMap<>();
        output.put("qualityTaskId", taskId);
        output.put("qualityTaskStatus", taskStatus);
        output.put("qualityScore", score);
        output.put("qualitySummary", summary);
        output.put("qualityDeviationCount", deviationCount);
        output.put("qualityWarningCount", warningCount);
        output.put("qualityCheckedAt", java.time.LocalDateTime.now().toString());
        output.put("qualityExecutionStrategy", executionStrategy);
        if (crossReviewId != null) {
            output.put("crossReviewId", crossReviewId);
        }
        return output;
    }

    /** 空结果 */
    public static QualityCheckResult empty(String taskId) {
        return new QualityCheckResult(
                taskId, GateDecisionEnum.PASS.getCode(), List.of(), 100,
                "未发现问题", null, 0, ExecutionStrategyEnum.SYNC.getCode(),
                TaskStatusEnum.SUCCEEDED.getCode(), 0, 0
        );
    }
}
