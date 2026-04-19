package com.schemaplexai.model.dto.gateway;

import lombok.Data;

@Data
public class ApiGatewayLogQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String callerType;

    private Boolean success;
}
