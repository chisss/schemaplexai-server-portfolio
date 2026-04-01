package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全事件视图对象
 */
@Data
public class SecurityIncidentVO {

    private String id;

    private String tenantId;

    private String incidentNo;

    private String traceId;

    private String domainCode;

    private String riskLevel;

    private String status;

    private String sourceType;

    private String sourceId;

    private String sourceName;

    private String policyId;

    private String policyCode;

    private String decision;

    private String eventTitle;

    private String eventDetail;

    private String assignedTo;

    private String assignedName;

    private LocalDateTime resolvedAt;

    private String resolutionSummary;

    private Map<String, Object> payload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<SecurityIncidentActionVO> actions;
}
