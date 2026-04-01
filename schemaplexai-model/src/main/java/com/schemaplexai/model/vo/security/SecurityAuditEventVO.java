package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全审计事件视图对象
 */
@Data
public class SecurityAuditEventVO {

    private String id;

    private String tenantId;

    private String traceId;

    private String eventType;

    private String eventSource;

    private String eventStatus;

    private String riskLevel;

    private String domainCode;

    private String policyId;

    private String policyCode;

    private String resourceType;

    private String resourceId;

    private String eventTitle;

    private String eventDetail;

    private String actorUserId;

    private String actorUsername;

    private String clientIp;

    private String userAgent;

    private Map<String, Object> metadata;

    private LocalDateTime occurredAt;
}
