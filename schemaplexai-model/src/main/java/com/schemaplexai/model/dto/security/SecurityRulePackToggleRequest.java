package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 安全规则包状态切换请求
 */
@Data
public class SecurityRulePackToggleRequest {

    @NotBlank(message = "状态不能为空")
    private String status;
}
