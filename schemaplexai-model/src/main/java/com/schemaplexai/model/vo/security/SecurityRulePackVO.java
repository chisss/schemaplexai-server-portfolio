package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全规则包视图对象
 */
@Data
public class SecurityRulePackVO {

    private String id;

    private String tenantId;

    private String packCode;

    private String packName;

    private String industryCode;

    private String status;

    private Integer packVersion;

    private String defaultAction;

    private Boolean isBuiltin;

    private String description;

    private Map<String, Object> packConfig;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<SecurityRuleItemVO> items;

    private List<SecurityBindingVO> bindings;
}
