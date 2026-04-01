package com.schemaplexai.model.vo.workspace;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作空间分支差异视图
 */
@Data
public class WorkspaceBranchDiffVO {

    private String workspaceId;
    private String workspaceName;
    private String sourceBranch;
    private String targetBranch;
    private Integer aheadCount;
    private Integer behindCount;
    private Integer changedFileCount;
    private Integer additions;
    private Integer deletions;
    private List<BranchDiffFileVO> files = new ArrayList<>();
}
