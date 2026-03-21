package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 集成配置表实体
 */
@Data
@TableName(value = "sf_integration", autoResultMap = true)
public class Integration implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 集成类型: git/cicd/pm/im */
    private String integrationType;

    /** 平台: github/gitlab/gitee/jenkins/... */
    private String platform;

    /** 名称 */
    private String name;

    /** 配置信息(OAuth/API Key等，加密) */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    /** Webhook签名密钥 */
    private String webhookSecret;

    /** 状态: active/inactive */
    private String status;

    /** 最近同步时间 */
    private LocalDateTime lastSyncAt;

    /** 错误信息 */
    private String errorMessage;

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
