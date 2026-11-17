package com.schemaplexai.model.vo.cost;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 预算阻断事件
 */
@Data
public class BudgetBlockEventVO {

    private String eventId;

    private String resourceType;

    private String resourceId;

    private String budgetId;

    private String strategy;

    private Double usageRate;

    private String remainingAmount;

    private String traceId;

    private String severity;

    private String message;

    private LocalDateTime occurredAt;
}
