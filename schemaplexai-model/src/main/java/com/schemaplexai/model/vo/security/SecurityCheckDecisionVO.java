package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.util.List;

/**
 * 安全检查决策视图对象
 */
@Data
public class SecurityCheckDecisionVO {

    private String decision;

    private String traceId;

    private String incidentId;

    private String message;

    private String userActionTip;

    private String adminActionTip;

    private Boolean shouldCreateIncident;

    private List<SecurityMatchedRuleVO> matchedPolicies;

    private List<SecurityMatchedRuleVO> matchedRulePacks;
}
