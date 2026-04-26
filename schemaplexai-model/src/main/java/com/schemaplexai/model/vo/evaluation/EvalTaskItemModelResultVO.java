package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 评估任务单条模型结果视图
 */
@Data
public class EvalTaskItemModelResultVO {

    private String modelId;

    private String modelName;

    private String outputText;

    private Long latencyMs;

    private BigDecimal cost;

    private Integer score;

    private String status;

    private String errorMessage;

    private Integer outputLength;
}
