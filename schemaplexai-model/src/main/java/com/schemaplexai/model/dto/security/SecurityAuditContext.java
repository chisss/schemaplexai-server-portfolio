package com.schemaplexai.model.dto.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 安全审计上下文
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SecurityAuditContext {

    private String clientIp;

    private String userAgent;
}
