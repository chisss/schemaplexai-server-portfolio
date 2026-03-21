package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 路由规则VO
 */
@Data
public class AiModelRouteVO {

    private String id;
    private String name;
    private Integer priority;
    private String matchDimension;
    private Map<String, Object> matchCondition;
    private String primaryModelId;
    private String primaryModelName;
    private String secondaryModelId;
    private String secondaryModelName;
    private String tertiaryModelId;
    private String tertiaryModelName;
    private Map<String, Object> primaryTriggerCondition;
    private Map<String, Object> secondaryTriggerCondition;
    private String description;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
