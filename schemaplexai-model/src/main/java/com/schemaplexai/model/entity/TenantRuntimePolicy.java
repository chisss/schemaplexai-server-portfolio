package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 租户运行时策略实体
 */
@Data
@TableName("sf_tenant_runtime_policy")
public class TenantRuntimePolicy implements Serializable {

    @TableId
    private String tenantId;

    private String workspaceRootPath;

    private Integer maxWorkspaceGb;

    private Integer maxExecutionMinutes;

    private String sandboxProfile;

    private Boolean allowLocalImport;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
