package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预算使用情况VO
 */
@Data
public class BudgetUsageVO {
    private BigDecimal budgetAmount;
    private BigDecimal usedAmount;
    private BigDecimal remainAmount;
    private Double usageRate;
    /** 告警级别: normal/warning_50/warning_80/exceeded */
    private String alertLevel;
    private LocalDateTime cycleStart;
    private LocalDateTime cycleEnd;
}
