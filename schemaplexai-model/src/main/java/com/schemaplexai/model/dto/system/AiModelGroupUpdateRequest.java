package com.schemaplexai.model.dto.system;

import lombok.Data;

/**
 * 更新AI模型组请求DTO
 */
@Data
public class AiModelGroupUpdateRequest {

    private String name;

    private String description;

    /** 路由策略: manual/by_price/by_performance */
    private String routingStrategy;

    /** 状态: active/inactive */
    private String status;
}
