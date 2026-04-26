package com.schemaplexai.model.vo.monitor;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 调用链 Span 视图
 */
@Data
public class AgentTraceSpanVO {

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String agentId;

    private String spanType;

    private String name;

    private Long durationMs;

    private Long inputTokens;

    private Long outputTokens;

    private BigDecimal cost;

    private String status;

    private String failureCategory;

    private String failureReason;

    private Boolean recoverable;

    private LocalDateTime startedAt;

    private LocalDateTime createdAt;
}
