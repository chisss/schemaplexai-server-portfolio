package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

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

    /**
     * TRAJECTORY 模式关联的 Agent 执行 ID（轨迹回放入口）
     */
    private String executionId;

    /**
     * LLM-as-Judge 共识分（0-100，null=未启用 Judge）
     */
    private Integer judgeScore;

    /**
     * Judge findings 明细（JSONB 原样透出）
     */
    private Map<String, Object> judgeDetail;
}
