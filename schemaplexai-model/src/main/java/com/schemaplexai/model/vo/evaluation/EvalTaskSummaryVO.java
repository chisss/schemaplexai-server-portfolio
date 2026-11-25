package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估任务概要视图（分页列表专用，不含结果明细）
 */
@Data
public class EvalTaskSummaryVO {

    private String id;

    private String name;

    private String datasetId;

    private String datasetName;

    /**
     * 评估类型：QA=单轮问答，TRAJECTORY=Agent 执行轨迹
     */
    private String taskType;

    /**
     * 被测对象名称列表（QA=模型名，TRAJECTORY=Agent 名）
     */
    private List<String> targetNames;

    /**
     * LLM-as-Judge 模型名称列表
     */
    private List<String> judgeModelNames;

    private String status;

    private Integer totalItems;

    private Integer completedItems;

    private Double progress;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
