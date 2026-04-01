package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全事件动作视图对象
 */
@Data
public class SecurityIncidentActionVO {

    private String id;

    private String incidentId;

    private String actionType;

    private String actionResult;

    private String comment;

    private Map<String, Object> metadata;

    private String operatorId;

    private String operatorName;

    private LocalDateTime actionAt;
}
