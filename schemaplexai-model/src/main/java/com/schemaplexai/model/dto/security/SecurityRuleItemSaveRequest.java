package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 安全规则项保存请求
 */
@Data
public class SecurityRuleItemSaveRequest {

    private String id;

    @NotBlank(message = "规则项编码不能为空")
    private String itemCode;

    @NotBlank(message = "规则项名称不能为空")
    private String itemName;

    private String ruleSource;

    private String ruleClause;

    @NotBlank(message = "风险等级不能为空")
    private String riskLevel;

    @NotBlank(message = "动作不能为空")
    private String action;

    @NotBlank(message = "匹配类型不能为空")
    private String matchType;

    @NotBlank(message = "匹配内容不能为空")
    private String matchContent;

    private Integer sortOrder;

    private Boolean enabled;

    private Map<String, Object> itemConfig;
}
