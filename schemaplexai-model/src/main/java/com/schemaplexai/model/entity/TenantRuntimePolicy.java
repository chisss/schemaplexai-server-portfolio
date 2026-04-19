package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户运行时策略实体
 */
@Data
@TableName(value = "sf_tenant_runtime_policy", autoResultMap = true)
public class TenantRuntimePolicy implements Serializable {

    @TableId
    private String tenantId;

    private String workspaceRootPath;

    private Integer maxWorkspaceGb;

    private Integer maxExecutionMinutes;

    private String sandboxProfile;

    private Boolean allowLocalImport;

    /**
     * sys.bash 允许执行的基础命令白名单
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> sandboxAllowedCommands;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
