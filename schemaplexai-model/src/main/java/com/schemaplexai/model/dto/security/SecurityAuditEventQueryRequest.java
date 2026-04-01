package com.schemaplexai.model.dto.security;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全审计事件查询请求
 */
@Data
public class SecurityAuditEventQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String domainCode;

    private String eventType;

    private String eventStatus;

    private String riskLevel;

    private String traceId;

    private String keyword;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
