package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 模型评估结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_eval_result")
public class EvalResult extends BaseEntity {

    /**
     * 任务 ID
     */
    private String taskId;

    /**
     * 模型 ID
     */
    private String modelId;

    /**
     * 数据集条目 ID
     */
    private String itemId;

    /**
     * 模型输出
     */
    private String outputText;

    /**
     * 延迟（毫秒）
     */
    private Long latencyMs;

    /**
     * 本次调用成本
     */
    private BigDecimal cost;

    /**
     * 评分（0-100）
     */
    private Integer score;

    /**
     * 结果状态：SUCCESS/FAILED
     */
    private String status;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 输出长度
     */
    private Integer outputLength;
}
