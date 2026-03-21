package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 预算告警VO
 */
@Data
public class BudgetAlertVO {
    private String budgetId;
    private String budgetLevel;
    private String targetName;
    private Double usageRate;
    private String alertLevel;
    private LocalDateTime alertTime;
}
