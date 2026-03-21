package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class DeviationStatusUpdateRequest {
    @NotBlank(message = "状态不能为空")
    private String status;
    private String remark;
}
