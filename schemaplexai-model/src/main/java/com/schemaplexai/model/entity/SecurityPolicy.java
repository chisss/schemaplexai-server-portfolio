package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全策略实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_policy", autoResultMap = true)
public class SecurityPolicy extends BaseEntity {

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

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> controlPoints;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> targetSelector;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> policyConfig;

    private String description;

    private LocalDateTime lastPublishedAt;
}
