package com.schemaplexai.model.dto.evaluation;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 评估数据集条目请求
 */
@Data
public class EvalDatasetItemRequest {

    @NotBlank(message = "评估输入不能为空")
    private String inputText;

    private String expectedOutput;

    private Map<String, Object> metadata;

    private Integer sortOrder;
}
