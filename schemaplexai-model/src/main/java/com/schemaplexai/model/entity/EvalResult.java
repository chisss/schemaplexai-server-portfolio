package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 模型评估结果
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_eval_result", autoResultMap = true)
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

    /**
     * TRAJECTORY 模式关联的 Agent 执行 ID（sf_agent_execution.id），轨迹可回放
     */
    private String executionId;

    /**
     * LLM-as-Judge 共识分（0-100，null = 未启用 Judge）
     */
    private Integer judgeScore;

    /**
     * Judge findings 明细（JSONB：维度分/共识/独立问题列表）
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> judgeDetail;

    /**
     * 执行时快照的数据集输入（历史结果自洽，不随数据集修改漂移）
     */
    private String inputSnapshot;

    /**
     * 执行时快照的期望输出
     */
    private String expectedSnapshot;
}
