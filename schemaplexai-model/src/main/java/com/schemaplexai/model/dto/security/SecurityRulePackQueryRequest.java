package com.schemaplexai.model.dto.security;

import lombok.Data;

/**
 * 安全规则包查询请求
 */
@Data
public class SecurityRulePackQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String industryCode;

    private String status;

    private String keyword;
}
