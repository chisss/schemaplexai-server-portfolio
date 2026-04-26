package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 路由决策日志视图
 */
@Data
public class RoutingDecisionLogVO {

    private String id;

    private String agentId;

    private String agentName;

    private String requestId;

    private String selectedModelId;

    private String selectedModelName;

    private String strategy;

    private List<String> candidateModels;

    private String reason;

    private Long latencyMs;

    private Long tokenCount;

    private BigDecimal cost;

    private LocalDateTime createdAt;
}
