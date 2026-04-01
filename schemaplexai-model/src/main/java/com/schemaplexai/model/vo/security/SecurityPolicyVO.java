package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全策略视图对象
 */
@Data
public class SecurityPolicyVO {

    private String id;

    private String tenantId;

    private String policyCode;

    private String policyName;

    private String domainCode;

    private String policyType;

    private String policyScope;

    private String status;

    private String enforcementMode;

    private String riskLevel;

    private Integer version;

    private Boolean isBuiltin;

    private List<String> controlPoints;

    private List<String> tags;

    private Map<String, Object> targetSelector;

    private Map<String, Object> policyConfig;

    private String description;

    private String createdBy;

    private String updatedBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime lastPublishedAt;
}
