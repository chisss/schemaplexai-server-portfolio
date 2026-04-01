package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workspace.BranchRuleSaveRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceBranchQueryRequest;
import com.schemaplexai.model.vo.workspace.BranchRuleVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchDiffVO;
import com.schemaplexai.model.vo.workspace.WorkspaceBranchVO;
import com.schemaplexai.service.workspace.WorkspaceBranchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工作空间分支管理控制器
 */
@RestController
@RequestMapping("/workspace-branches")
@RequiredArgsConstructor
@Tag(name = "工作空间分支管理")
public class WorkspaceBranchController {

    private final WorkspaceBranchService workspaceBranchService;

    @GetMapping
    @Operation(summary = "分页查询工作空间分支")
    public R<PageResult<WorkspaceBranchVO>> page(WorkspaceBranchQueryRequest request) {
        return R.ok(workspaceBranchService.pageBranches(request));
    }

    @GetMapping("/diff")
    @Operation(summary = "获取分支差异")
    public R<WorkspaceBranchDiffVO> diff(@RequestParam String workspaceId,
                                         @RequestParam String sourceBranch,
                                         @RequestParam(required = false) String targetBranch) {
        return R.ok(workspaceBranchService.getBranchDiff(workspaceId, sourceBranch, targetBranch));
    }

    @GetMapping("/rules")
    @Operation(summary = "查询分支规则")
    public R<List<BranchRuleVO>> listRules(@RequestParam(required = false) String workspaceId) {
        return R.ok(workspaceBranchService.listBranchRules(workspaceId));
    }

    @PostMapping("/rules")
    @Operation(summary = "创建分支规则")
    public R<BranchRuleVO> createRule(@Valid @RequestBody BranchRuleSaveRequest request) {
        return R.ok(workspaceBranchService.createBranchRule(request));
    }

    @PutMapping("/rules/{id}")
    @Operation(summary = "更新分支规则")
    public R<BranchRuleVO> updateRule(@PathVariable String id, @Valid @RequestBody BranchRuleSaveRequest request) {
        return R.ok(workspaceBranchService.updateBranchRule(id, request));
    }

    @DeleteMapping("/rules/{id}")
    @Operation(summary = "删除分支规则")
    public R<Void> deleteRule(@PathVariable String id) {
        workspaceBranchService.deleteBranchRule(id);
        return R.ok();
    }
}
