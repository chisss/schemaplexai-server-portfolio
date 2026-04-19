package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_api_gateway", autoResultMap = true)
public class ApiGateway extends BaseEntity {

    private String name;

    private String description;

    private String direction;

    private String method;

    private String url;

    private String authType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> authConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> headers;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> queryParams;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestBodySchema;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responseBodySchema;

    private String contentType;

    private Integer timeoutMs;

    private Integer retryCount;

    private String category;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    private String status;
}
