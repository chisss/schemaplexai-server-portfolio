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
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 通知渠道实体
 */
@Data
@TableName(value = "sf_notification_channel", autoResultMap = true)
public class NotificationChannel implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 渠道名称 */
    private String name;

    /** 渠道类型: dingtalk/wechat_work/feishu/slack/email/sms */
    private String channelType;

    /** 配置信息(webhook_url/app_key等) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    /** 状态: active/inactive/error */
    private String status;

    /** 通知规则 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> notificationRules;

    /** 最近测试时间 */
    private LocalDateTime lastTestAt;

    /** 错误信息 */
    private String errorMessage;

    /** 描述 */
    private String description;

    /** 创建人 */
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新人 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
