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

    @Valid
    private List<EvalDatasetItemRequest> items;
}
