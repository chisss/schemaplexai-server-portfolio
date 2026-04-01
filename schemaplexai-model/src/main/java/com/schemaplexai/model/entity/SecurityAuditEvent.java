package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全审计事件实体
 */
@Data
@TableName(value = "sf_security_audit_event", autoResultMap = true)
public class SecurityAuditEvent implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String traceId;

    private String eventType;

    private String eventSource;

    private String eventStatus;

    private String riskLevel;

    private String domainCode;

    private String policyId;

    private String policyCode;

    private String resourceType;

    private String resourceId;

    private String eventTitle;

    private String eventDetail;

    private String actorUserId;

    private String actorUsername;

    private String clientIp;

    private String userAgent;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private LocalDateTime occurredAt;
}
