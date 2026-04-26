package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 评估任务模型汇总视图
 */
@Data
public class EvalTaskModelSummaryVO {

    private String modelId;

    private String modelName;

    private Double avgLatencyMs;

    private BigDecimal avgCost;

    private Double avgScore;

    private Double successRate;

    private Double avgOutputLength;
}
