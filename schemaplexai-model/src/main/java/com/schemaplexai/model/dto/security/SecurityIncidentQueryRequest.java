package com.schemaplexai.model.dto.security;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 安全事件查询请求
 */
@Data
public class SecurityIncidentQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String domainCode;

    private String status;

    private String riskLevel;

    private String sourceType;

    private String decision;

    private String keyword;

    private String traceId;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
