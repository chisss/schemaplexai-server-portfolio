package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.util.Map;

/**
 * 安全规则项视图对象
 */
@Data
public class SecurityRuleItemVO {

    private String id;

    private String packId;

    private String itemCode;

    private String itemName;

    private String ruleSource;

    private String ruleClause;

    private String riskLevel;

    private String action;

    private String matchType;

    private String matchContent;

    private Integer sortOrder;

    private Boolean enabled;

    private Map<String, Object> itemConfig;
}
