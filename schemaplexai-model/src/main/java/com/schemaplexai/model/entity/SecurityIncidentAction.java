package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 安全风控事件处置记录实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_security_incident_action", autoResultMap = true)
public class SecurityIncidentAction extends BaseEntity {

    private String incidentId;

    private String actionType;

    private String actionResult;

    private String comment;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private String operatorId;

    private String operatorName;

    private LocalDateTime actionAt;
}
