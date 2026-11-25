package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 创建评估数据集请求
 */
@Data
public class EvalDatasetCreateRequest {

    @NotBlank(message = "数据集名称不能为空")
    private String name;

    private String description;

    /** 通过分数线（0-100，可选；null=不启用 PASS/FAIL 判定） */
    private Integer passScoreThreshold;

    @Valid
    private List<EvalDatasetItemRequest> items;
}
