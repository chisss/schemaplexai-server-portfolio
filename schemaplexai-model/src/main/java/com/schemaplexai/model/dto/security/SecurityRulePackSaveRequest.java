package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 安全规则包保存请求
 */
@Data
public class SecurityRulePackSaveRequest {

    @NotBlank(message = "规则包编码不能为空")
    private String packCode;

    @NotBlank(message = "规则包名称不能为空")
    private String packName;

    @NotBlank(message = "行业编码不能为空")
    private String industryCode;

    @NotBlank(message = "默认动作不能为空")
    private String defaultAction;

    private String description;

    private Map<String, Object> packConfig;

    private List<SecurityRuleItemSaveRequest> items;
}
