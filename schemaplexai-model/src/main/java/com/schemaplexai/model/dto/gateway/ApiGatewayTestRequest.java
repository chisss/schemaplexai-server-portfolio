package com.schemaplexai.model.dto.gateway;

import lombok.Data;

import java.util.Map;

@Data
public class ApiGatewayTestRequest {

    private Map<String, Object> headers;

    private Map<String, Object> queryParams;

    private String body;
}
