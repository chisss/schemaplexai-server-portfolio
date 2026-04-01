package com.schemaplexai.model.dto.workspace;

import lombok.Data;

/**
 * 工作空间分支查询请求
 */
@Data
public class WorkspaceBranchQueryRequest {

    private Integer page = 1;
    private Integer size = 20;
    private String workspaceId;
    private String requirementNo;
    private String branchName;
}
