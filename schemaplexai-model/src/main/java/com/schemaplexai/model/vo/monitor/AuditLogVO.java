package com.schemaplexai.model.vo.monitor;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志VO
 */
@Data
public class AuditLogVO {
    private String id;
    private String userId;
    private String username;
    private String action;
    private String resource;
    private String resourceId;
    private String detail;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}
