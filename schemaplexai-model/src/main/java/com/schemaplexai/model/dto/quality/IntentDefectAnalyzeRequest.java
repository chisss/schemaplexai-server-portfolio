package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class IntentDefectAnalyzeRequest {
    @NotBlank(message = "Spec ID不能为空")
    private String specId;
    @NotBlank(message = "文档类型不能为空")
    private String docType;
}
