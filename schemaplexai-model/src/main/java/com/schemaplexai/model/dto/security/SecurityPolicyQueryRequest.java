package com.schemaplexai.model.dto.security;

import lombok.Data;

/**
 * 安全策略分页查询请求
 */
@Data
public class SecurityPolicyQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String domainCode;

    private String status;

    private String riskLevel;

    private String keyword;
}
