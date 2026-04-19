package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_api_gateway_policy", autoResultMap = true)
public class ApiGatewayPolicy extends BaseEntity {

    private String gatewayId;

    private Integer rateLimitPerMinute;

    private Integer rateLimitPerHour;

    private Integer rateLimitPerDay;

    private BigDecimal costPerCall;

    private BigDecimal dailyCostLimit;

    private BigDecimal monthlyCostLimit;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> ipWhitelist;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Object> dataMaskingRules;

    private String accessLevel;

    private Boolean requireApproval;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> complianceTags;

    private Boolean enabled;
}
