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
