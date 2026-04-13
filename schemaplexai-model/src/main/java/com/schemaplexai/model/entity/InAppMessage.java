package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
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
 * 站内信主表实体
 */
@Data
@TableName(value = "sf_in_app_message", autoResultMap = true)
public class InAppMessage implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String title;

    private String content;

    private String messageType;

    private String businessType;

    private String sourceType;

    private String sourceId;

    private String actionUrl;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payload;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
