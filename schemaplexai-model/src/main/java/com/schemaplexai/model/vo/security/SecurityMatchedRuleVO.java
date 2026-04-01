package com.schemaplexai.model.vo.security;

import lombok.Data;

/**
 * 命中策略/规则项视图对象
 */
@Data
public class SecurityMatchedRuleVO {

    private String sourceType;

    private String sourceId;

    private String sourceCode;

    private String sourceName;

    private String itemId;

    private String itemCode;

    private String itemName;

    private String riskLevel;

    private String action;

    private String hitReason;
}
