package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建AI模型组请求DTO
 */
@Data
public class AiModelGroupCreateRequest {

    @NotBlank(message = "模型组名称不能为空")
    private String name;

    private String description;

    /** 路由策略: manual/by_price/by_performance，默认manual */
    private String routingStrategy = "manual";
}
