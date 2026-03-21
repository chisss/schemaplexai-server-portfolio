package com.schemaplexai.model.dto.monitor;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志查询请求
 */
@Data
public class AuditLogQueryRequest {
    private String userId;
    private String action;
    private String resource;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer page = 1;
    private Integer size = 20;
}
