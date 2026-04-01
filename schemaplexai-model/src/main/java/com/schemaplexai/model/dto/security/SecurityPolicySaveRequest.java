package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 安全策略保存请求
 */
@Data
public class SecurityPolicySaveRequest {

    @NotBlank(message = "策略编码不能为空")
    private String policyCode;

    @NotBlank(message = "策略名称不能为空")
    private String policyName;

    @NotBlank(message = "安全域不能为空")
    private String domainCode;

    @NotBlank(message = "策略类型不能为空")
    private String policyType;

    @NotBlank(message = "生效范围不能为空")
    private String policyScope;

    @NotBlank(message = "执行模式不能为空")
    private String enforcementMode;

    @NotBlank(message = "风险等级不能为空")
    private String riskLevel;

    private List<String> controlPoints;

    private List<String> tags;

    private Map<String, Object> targetSelector;

    private Map<String, Object> policyConfig;

    private String description;
}
