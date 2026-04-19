package com.schemaplexai.model.dto.gateway;

import lombok.Data;

@Data
public class ApiGatewayQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String direction;

    private String status;

    private String category;

    private String keyword;
}
