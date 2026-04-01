package com.schemaplexai.model.dto.quality;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 质量配置组绑定请求
 */
@Data
public class QualityProfileBindingRequest {

    @NotBlank(message = "绑定类型不能为空")
    private String bindingType;

    @NotBlank(message = "绑定对象不能为空")
    private String bindingId;
}
