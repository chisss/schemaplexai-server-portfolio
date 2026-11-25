package com.schemaplexai.model.dto.evaluation;

import lombok.Data;

/**
 * 更新评估数据集请求
 */
@Data
public class EvalDatasetUpdateRequest {

    private String name;

    private String description;

    /** 通过分数线（0-100，可选；null=清除阈值） */
    private Integer passScoreThreshold;
}
