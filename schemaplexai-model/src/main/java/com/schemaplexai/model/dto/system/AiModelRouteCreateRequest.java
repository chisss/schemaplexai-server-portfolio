package com.schemaplexai.model.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 创建路由规则请求DTO
 */
@Data
public class AiModelRouteCreateRequest {

    /** 路由规则名称 */
    @NotBlank(message = "路由规则名称不能为空")
    private String name;

    /** 优先级 */
    private Integer priority;

    /** 匹配维度: task_type/model_capability/cost_budget */
    @NotBlank(message = "匹配维度不能为空")
    private String matchDimension;

    /** 匹配条件 */
    private Map<String, Object> matchCondition;

    /** 主模型ID */
    @NotBlank(message = "主模型不能为空")
    private String primaryModelId;

    /** 备用模型ID */
    private String secondaryModelId;

    /** 兜底模型ID */
    private String tertiaryModelId;

    /** 主模型降级触发条件 */
    private Map<String, Object> primaryTriggerCondition;

    /** 备用模型降级触发条件 */
    private Map<String, Object> secondaryTriggerCondition;

    /** 描述 */
    private String description;
}
