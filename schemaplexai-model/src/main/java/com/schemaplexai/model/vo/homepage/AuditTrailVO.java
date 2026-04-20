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
    private String detail;
    private String createdAt;
}
