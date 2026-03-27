package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI模型组实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sf_ai_model_group")
public class AiModelGroup extends BaseEntity {

    /** 模型组名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 路由策略: manual/by_price/by_performance */
    private String routingStrategy;

    /** 状态: active/inactive */
    private String status;
}
