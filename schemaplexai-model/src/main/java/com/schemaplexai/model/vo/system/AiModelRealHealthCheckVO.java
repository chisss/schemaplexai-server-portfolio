package com.schemaplexai.model.vo.system;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 模型真实协议健康检查结果
 */
@Data
public class AiModelRealHealthCheckVO {

    private String modelId;

    private String modelName;

    private Boolean success;

    private String category;

    private String protocol;

    private String baseUrl;

    private Long latencyMs;

    private Long inputTokens;

    private Long outputTokens;

    private BigDecimal estimatedCost;

    private Boolean priceConfigured;

    private String message;

    private LocalDateTime testedAt;
}
