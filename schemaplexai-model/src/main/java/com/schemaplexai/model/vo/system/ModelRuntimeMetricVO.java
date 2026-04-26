package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型实时指标视图
 */
@Data
public class ModelRuntimeMetricVO {

    private String modelId;

    private String modelName;

    private Long requestCount1m;

    private Double errorRate1m;

    private Long p95LatencyMs;

    private String healthStatus;

    private LocalDateTime lastUpdated;
}
