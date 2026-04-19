package com.schemaplexai.model.dto.gateway;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class ApiGatewayPolicyRequest {

    private Integer rateLimitPerMinute;

    private Integer rateLimitPerHour;

    private Integer rateLimitPerDay;

    private BigDecimal costPerCall;

    private BigDecimal dailyCostLimit;

    private BigDecimal monthlyCostLimit;

    private List<String> ipWhitelist;

    private List<Object> dataMaskingRules;

    private String accessLevel;

    private Boolean requireApproval;

    private List<String> complianceTags;

    private Boolean enabled;
}
