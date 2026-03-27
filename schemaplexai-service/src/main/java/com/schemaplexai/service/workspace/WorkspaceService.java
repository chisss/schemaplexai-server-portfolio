package com.schemaplexai.service.workspace;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceQueryRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceUpdateRequest;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import com.schemaplexai.service.integration.git.GitOperationService;

import java.util.List;

/**
 * 工作空间服务接口
 */
public interface WorkspaceService {

    WorkspaceVO create(WorkspaceCreateRequest request);

    PageResult<WorkspaceVO> page(WorkspaceQueryRequest request);

    WorkspaceVO getById(String id);

    WorkspaceVO update(String id, WorkspaceUpdateRequest request);

    void delete(String id);

    WorkspaceVO sync(String id);

    List<WorkspaceVO> listAll();

    /**
     * 列出工作空间的所有分支
     */
    List<GitOperationService.BranchInfo> listBranches(String id);

    /**
     * 获取当前分支
     */
    String getCurrentBranch(String id);

    /**
     * 创建分支
     */
    GitOperationService.BranchInfo createBranch(String id, String branchName, String startPoint);
}
