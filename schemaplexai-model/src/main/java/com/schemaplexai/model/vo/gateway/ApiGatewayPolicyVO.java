package com.schemaplexai.model.vo.gateway;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ApiGatewayPolicyVO {

    private String id;

    private String gatewayId;

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

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
