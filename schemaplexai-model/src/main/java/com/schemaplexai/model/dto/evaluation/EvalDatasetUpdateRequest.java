package com.schemaplexai.model.dto.evaluation;

import lombok.Data;

/**
 * 更新评估数据集请求
 */
@Data
public class EvalDatasetUpdateRequest {

    private String name;

    private String description;
}
