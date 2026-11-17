package com.schemaplexai.model.vo.homepage;

import lombok.Data;

/**
 * 审计追踪
 */
@Data
public class AuditTrailVO {

    private String id;
    private String username;
    private String action;
    private String resource;
    private String resourceId;
    private String detail;
    private String severity;
    private String targetUrl;
    private String traceId;
    private String createdAt;
}
