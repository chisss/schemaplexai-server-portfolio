package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作空间分支实体
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_workspace_branch", autoResultMap = true)
public class WorkspaceBranch extends BaseEntity {

    private String workspaceId;
    private String branchName;
    private String requirementNo;
    private String sourceBranch;
    private String targetBranch;
    private String headCommit;
    private Integer behindCount;
    private Integer aheadCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> diffSummary;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    private String branchStatus;
    private Boolean isCurrent;
    private LocalDateTime lastSyncedAt;
}
