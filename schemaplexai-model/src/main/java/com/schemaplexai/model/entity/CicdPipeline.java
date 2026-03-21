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
 * CICD Pipeline实体
 */
@Data
@TableName(value = "sf_cicd_pipeline", autoResultMap = true)
public class CicdPipeline implements Serializable {

    /** 主键ID */
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户ID */
    private String tenantId;

    /** 关联集成ID */
    private String integrationId;

    /** 关联工作空间ID */
    private String workspaceId;

    /** Pipeline名称 */
    private String name;

    /** Pipeline类型: jenkins/gitlab_ci/github_actions */
    private String pipelineType;

    /** Pipeline配置 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;

    /** 触发规则 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> triggerRules;

    /** 状态: inactive/active */
    private String status;

    /** 最近运行时间 */
    private LocalDateTime lastRunAt;

    /** 最近运行状态 */
    private String lastRunStatus;

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
