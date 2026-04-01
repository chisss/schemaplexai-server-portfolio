package com.schemaplexai.model.vo.security;

import lombok.Data;

/**
 * 安全域概览视图对象
 */
@Data
public class SecurityDomainOverviewVO {

    private String domainCode;

    private Long totalPolicies;

    private Long activePolicies;

    private Long blockedEvents24h;
}
