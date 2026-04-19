package com.schemaplexai.model.vo.gateway;

import lombok.Data;

import java.util.Map;

@Data
public class ApiGatewayTestResult {

    private Boolean success;

    private Integer statusCode;

    private Map<String, Object> responseHeaders;

    private String responseBody;

    private Long durationMs;

    private String errorMessage;
}
