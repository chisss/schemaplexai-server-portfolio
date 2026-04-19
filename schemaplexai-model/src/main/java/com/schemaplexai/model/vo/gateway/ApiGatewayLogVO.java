package com.schemaplexai.model.vo.gateway;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class ApiGatewayLogVO {

    private String id;

    private String gatewayId;

    private String gatewayName;

    private String callerType;

    private String callerId;

    private String requestUrl;

    private String requestMethod;

    private Map<String, Object> requestHeaders;

    private String requestBody;

    private Integer responseStatus;

    private Map<String, Object> responseHeaders;

    private String responseBody;

    private Long durationMs;

    private BigDecimal cost;

    private Boolean success;

    private String errorMessage;

    private LocalDateTime createdAt;
}
