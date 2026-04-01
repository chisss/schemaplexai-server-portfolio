package com.schemaplexai.model.vo.workspace;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作空间分支视图
 */
@Data
public class WorkspaceBranchVO {

    private String id;
    private String workspaceId;
    private String workspaceName;
    private String branchName;
    private String requirementNo;
    private String sourceBranch;
    private String targetBranch;
    private String headCommit;
    private Integer aheadCount;
    private Integer behindCount;
    private Boolean isCurrent;
    private String branchStatus;
    private LocalDateTime lastSyncedAt;
    private Map<String, Object> diffSummary;
}
