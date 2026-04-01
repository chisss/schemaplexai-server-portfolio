package com.schemaplexai.service.workspace;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workspace.BranchRuleSaveRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceBranchQueryRequest;
import com.schemaplexai.model.vo.workspace.BranchRuleVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchDiffVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchVO;

import java.util.List;

/**
 * 工作空间分支管理服务
 */
public interface WorkspaceBranchService {

    PageResult<WorkspaceBranchVO> pageBranches(WorkspaceBranchQueryRequest request);

    WorkspaceBranchDiffVO getBranchDiff(String workspaceId, String sourceBranch, String targetBranch);

    List<BranchRuleVO> listBranchRules(String workspaceId);

    BranchRuleVO createBranchRule(BranchRuleSaveRequest request);

    BranchRuleVO updateBranchRule(String id, BranchRuleSaveRequest request);

    void deleteBranchRule(String id);
}
