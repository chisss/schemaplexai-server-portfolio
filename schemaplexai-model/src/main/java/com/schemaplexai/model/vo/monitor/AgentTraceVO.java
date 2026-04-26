package com.schemaplexai.model.vo.monitor;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Agent 调用链概览视图
 */
@Data
public class AgentTraceVO {

    private String traceId;

    private String agentId;

    private String agentName;

    private String status;

    private Long durationMs;

    private Long inputTokens;

    private Long outputTokens;

    private BigDecimal cost;

    private LocalDateTime startedAt;

    private LocalDateTime createdAt;
}
