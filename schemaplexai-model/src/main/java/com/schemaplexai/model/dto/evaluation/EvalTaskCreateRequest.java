package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 创建评估任务请求
 */
@Data
public class EvalTaskCreateRequest {

    private String name;

    @NotBlank(message = "数据集不能为空")
    private String datasetId;

    @NotEmpty(message = "至少选择一个模型")
    private List<String> modelIds;
}
