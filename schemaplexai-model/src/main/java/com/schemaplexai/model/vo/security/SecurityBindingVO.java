package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全绑定关系视图对象
 */
@Data
public class SecurityBindingVO {

    private String id;

    private String sourceType;

    private String sourceId;

    private String sourceCode;

    private String sourceName;

    private String bindingType;

    private String targetId;

    private String targetName;

    private String domainCode;

    private String status;

    private Integer priority;

    private LocalDateTime effectiveFrom;

    private LocalDateTime effectiveTo;

    private Map<String, Object> bindingConfig;
}
