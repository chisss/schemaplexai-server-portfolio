package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.util.Map;

/**
 * 安全策略可绑定目标选项
 */
@Data
public class SecurityTargetOptionVO {

    private String scope;

    private String value;

    private String label;

    private String status;

    private Map<String, Object> extra;
}
