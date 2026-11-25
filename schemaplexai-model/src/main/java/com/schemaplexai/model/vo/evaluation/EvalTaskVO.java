package com.schemaplexai.model.vo.evaluation;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估任务视图
 */
@Data
public class EvalTaskVO {

    private String id;

    private String name;

    private String datasetId;

    private String datasetName;

    private List<String> modelIds;

    private List<String> modelNames;

    /**
     * 评估类型：QA=单轮问答，TRAJECTORY=Agent 执行轨迹
     */
    private String taskType;

    /**
     * TRAJECTORY 模式被测 Agent ID 列表
     */
    private List<String> agentIds;

    /**
     * TRAJECTORY 模式被测 Agent 名称列表
     */
    private List<String> agentNames;

    /**
     * LLM-as-Judge 模型 ID 列表
     */
    private List<String> judgeModelIds;

    /**
     * LLM-as-Judge 模型名称列表
     */
    private List<String> judgeModelNames;

    /**
     * 通过分数线（任务创建时快照），null=不启用 PASS/FAIL 判定
     */
    private Integer passScoreThreshold;

    /**
     * 通过率（0-100，score>=threshold 的结果占比；未启用阈值时为 null）
     */
    private Double passRate;

    private String status;

    private Integer totalItems;

    private Integer completedItems;

    private Double progress;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private List<EvalTaskModelSummaryVO> modelSummaries;

    private List<EvalTaskResultItemVO> resultItems;
}
