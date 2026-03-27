package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 自动生成AI模型组请求DTO
 */
@Data
public class AiModelGroupAutoGenerateRequest {

    /** 策略: by_price=按价格排序（越低越优先）, by_performance=按性能排序（延迟越低越优先） */
    @NotBlank(message = "生成策略不能为空")
    private String strategy;

    /** 模型组名称，不填则自动生成 */
    private String name;
}
