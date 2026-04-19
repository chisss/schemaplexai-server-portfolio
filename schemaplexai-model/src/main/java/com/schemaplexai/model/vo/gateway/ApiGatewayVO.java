package com.schemaplexai.model.vo.gateway;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ApiGatewayVO {

    private String id;

    private String name;

    private String description;

    private String direction;

    private String method;

    private String url;

    private String authType;

    private Map<String, Object> authConfig;

    private Map<String, Object> headers;

    private Map<String, Object> queryParams;

    private Map<String, Object> requestBodySchema;

    private Map<String, Object> responseBodySchema;

    private String contentType;

    private Integer timeoutMs;

    private Integer retryCount;

    private String category;

    private List<String> tags;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
