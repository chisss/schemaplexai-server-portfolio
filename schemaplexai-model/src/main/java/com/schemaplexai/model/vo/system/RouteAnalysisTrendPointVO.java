package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 路由趋势点视图
 */
@Data
public class RouteAnalysisTrendPointVO {

    private String modelId;

    private String modelName;

    private LocalDateTime bucketTime;

    private Double avgLatencyMs;

    private BigDecimal avgCost;

    private Long requestCount;
}
