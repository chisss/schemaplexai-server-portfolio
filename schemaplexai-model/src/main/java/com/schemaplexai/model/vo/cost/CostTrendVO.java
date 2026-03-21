package com.schemaplexai.model.vo.cost;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 成本趋势VO
 */
@Data
public class CostTrendVO {
    private List<Map<String, Object>> costTrend;
    private List<Map<String, Object>> tokenTrend;
}
