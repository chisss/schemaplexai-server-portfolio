package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CrossReviewCreateRequest {
    @NotBlank(message = "Spec ID不能为空")
    private String specId;
    private String taskId;
    @NotBlank(message = "模型A ID不能为空")
    private String modelAId;
    @NotBlank(message = "模型B ID不能为空")
    private String modelBId;
}
