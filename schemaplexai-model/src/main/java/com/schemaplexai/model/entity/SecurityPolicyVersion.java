package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全策略版本快照实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_policy_version", autoResultMap = true)
public class SecurityPolicyVersion extends BaseEntity {

    private String policyId;

    private Integer versionNo;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> snapshotConfig;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> snapshotTargets;

    private String changeSummary;

    private String publishedBy;

    private LocalDateTime publishedAt;
}
