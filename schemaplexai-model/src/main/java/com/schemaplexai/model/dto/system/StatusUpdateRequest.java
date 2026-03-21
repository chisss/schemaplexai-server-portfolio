package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 通用状态更新请求DTO
 */
@Data
public class StatusUpdateRequest {

    /** 目标状态值 */
    @NotBlank(message = "状态值不能为空")
    private String status;
}
