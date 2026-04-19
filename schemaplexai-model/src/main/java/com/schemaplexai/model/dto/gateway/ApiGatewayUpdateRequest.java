package com.schemaplexai.model.dto.gateway;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ApiGatewayUpdateRequest {

    @Size(max = 128, message = "API名称不能超过128个字符")
    private String name;

    private String description;

    private String direction;

    private String method;

    @Size(max = 1024, message = "请求地址不能超过1024个字符")
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
}
