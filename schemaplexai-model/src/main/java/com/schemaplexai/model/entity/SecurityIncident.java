package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全风控事件实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_incident", autoResultMap = true)
public class SecurityIncident extends BaseEntity {

    private String incidentNo;

    private String traceId;

    private String domainCode;

    private String riskLevel;

    private String status;

    private String sourceType;

    private String sourceId;

    private String sourceName;

    private String policyId;

    private String policyCode;

    private String decision;

    private String eventTitle;

    private String eventDetail;

    private String assignedTo;

    private String assignedName;

    private LocalDateTime resolvedAt;

    private String resolutionSummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;
}
