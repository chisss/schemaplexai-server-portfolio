package com.schemaplexai.model.dto.system;

import lombok.Data;

import java.util.Map;

/**
 * 更新路由规则请求DTO
 */
@Data
public class AiModelRouteUpdateRequest {

    /** 路由规则名称 */
    private String name;

    /** 优先级 */
    private Integer priority;

    /** 匹配维度 */
    private String matchDimension;

    /** 匹配条件 */
    private Map<String, Object> matchCondition;

    /** 主模型ID */
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

    /** 状态 */
    private String status;
}
