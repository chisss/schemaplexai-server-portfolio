package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算VO
 */
@Data
public class BudgetVO {
    private String id;
    private String budgetLevel;
    private String targetId;
    private String targetName;
    private String budgetCycle;
    private BigDecimal budgetAmount;
    private Boolean alertThreshold50;
    private Boolean alertThreshold80;
    private Boolean alertThreshold100;
    private String overLimitStrategy;
    private String status;
    private LocalDateTime createdAt;
}
