package com.schemaplexai.model.dto.security;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 安全策略启停请求
 */
@Data
public class SecurityPolicyToggleRequest {

    @NotBlank(message = "目标状态不能为空")
    private String status;
}
