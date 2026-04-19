package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName(value = "sf_api_gateway_log", autoResultMap = true)
public class ApiGatewayLog implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String gatewayId;

    private String callerType;

    private String callerId;

    private String requestUrl;

    private String requestMethod;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestHeaders;

    private String requestBody;

    private Integer responseStatus;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responseHeaders;

    private String responseBody;

    private Long durationMs;

    private BigDecimal cost;

    private Boolean success;

    private String errorMessage;

    private LocalDateTime createdAt;
}
