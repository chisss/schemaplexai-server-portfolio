package com.schemaplexai.model.vo.evaluation;

import lombok.Builder;
import lombok.Data;

/**
 * 评估任务门禁判定结果（CI 回归门禁端点专用）
 */
@Data
@Builder
public class EvalTaskVerdictVO {

    /** 是否启用了阈值判定（数据集未配置阈值时 false） */
    private boolean enabled;

    /** 通过分数线（0-100） */
    private Integer threshold;

    /**
     * 判定结论：全部结果分数 >= 阈值且任务 DONE 才 PASS；
     * 未启用阈值 / 任务未完成 / 存在低于阈值的结果 → FAIL
     */
    private boolean pass;

    /** 不通过原因（pass=false 时填充） */
    private String reason;

    /** 任务状态 */
    private String taskStatus;

    /** 结果总数 */
    private int totalCount;

    /** 通过条数 */
    private int passedCount;

    /** 低于阈值条数（不含调用失败） */
    private int belowThresholdCount;

    /** 调用失败条数 */
    private int invokeFailCount;

    /** 通过率（0-100） */
    private double passRate;
}
