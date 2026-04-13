package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知记录实体
 */
@Data
@TableName(value = "sf_notification_record", autoResultMap = true)
public class NotificationRecord implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    private String channelId;

    private String channelName;

    private String channelType;

    private String templateId;

    private String templateName;

    private String templateType;

    private String sourceType;

    private String sourceId;

    private String businessType;

    private String workflowInstanceId;

    private String workflowNodeExecutionId;

    private String specId;

    private String status;

    private String title;

    private String content;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> requestPayload;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> responsePayload;

    private String responseSummary;

    private String providerMessageId;

    private String errorMessage;

    private BigDecimal billingAmount;

    private String billingCurrency;

    private String billingUnit;

    private Integer billingQuantity;

    private LocalDateTime sentAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
