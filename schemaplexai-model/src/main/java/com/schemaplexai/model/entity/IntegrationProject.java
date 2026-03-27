package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 集成项目关联表实体
 */
@Data
@TableName(value = "sf_integration_project", autoResultMap = true)
public class IntegrationProject implements Serializable {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String tenantId;
    private String integrationId;
    private String projectId;
    private String externalProjectId;
    private String externalProjectName;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> syncConfig;
    /** 状态: active/paused/error */
    private String status;
    private LocalDateTime lastSyncAt;
    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
    /** 本地项目路径（供Agent读取上下文） */
    private String localPath;
    @TableLogic
    private Integer deleted;
}
