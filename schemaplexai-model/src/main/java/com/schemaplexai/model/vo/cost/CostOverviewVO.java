package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

/**
 * 成本概览VO
 */
@Data
public class CostOverviewVO {
    private BigDecimal totalCost;
    private Long totalTokens;
    private BigDecimal todayCost;
    private Long todayTokens;
    /** 环比变化率 % */
    private Double costChangeRate;
    /** 预算使用率 % */
    private Double budgetUsageRate;
    private List<CostByDimensionVO> costByModel;
}
