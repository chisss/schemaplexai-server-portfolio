package com.schemaplexai.model.vo.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全策略版本视图对象
 */
@Data
public class SecurityPolicyVersionVO {

    private String id;

    private String policyId;

    private Integer versionNo;

    private Map<String, Object> snapshotConfig;

    private Map<String, Object> snapshotTargets;

    private String changeSummary;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
