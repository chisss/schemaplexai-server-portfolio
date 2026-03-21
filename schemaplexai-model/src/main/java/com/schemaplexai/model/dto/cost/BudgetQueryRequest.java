package com.schemaplexai.model.dto.cost;

import lombok.Data;

/**
 * 预算查询请求
 */
@Data
public class BudgetQueryRequest {
    private Integer page = 1;
    private Integer size = 20;
    private String budgetLevel;
    private String status;
}
