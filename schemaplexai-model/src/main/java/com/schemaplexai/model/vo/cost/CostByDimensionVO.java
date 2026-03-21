package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.math.BigDecimal;

/**
 * 通用维度成本分析VO
 */
@Data
public class CostByDimensionVO {
    private String dimensionId;
    private String dimensionName;
    private BigDecimal totalCost;
    private Long totalTokens;
    private Double percentage;
}
