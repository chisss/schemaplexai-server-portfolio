package com.schemaplexai.model.dto.cost;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 预算更新请求
 */
@Data
public class BudgetUpdateRequest {
    private BigDecimal budgetAmount;
    private Boolean alertThreshold50;
    private Boolean alertThreshold80;
    private Boolean alertThreshold100;
    private String overLimitStrategy;
    private String status;
}
