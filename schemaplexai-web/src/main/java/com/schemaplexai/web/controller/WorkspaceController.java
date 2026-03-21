package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceQueryRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceUpdateRequest;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import com.schemaplexai.service.workspace.WorkspaceService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作空间管理控制器
 */
@RestController
@RequestMapping("/workspaces")
@RequiredArgsConstructor
@Tag(name = "工作空间管理")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    @PostMapping
    @Operation(summary = "创建工作空间")
    public R<WorkspaceVO> create(@Valid @RequestBody WorkspaceCreateRequest request) {
        return R.ok(workspaceService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询工作空间")
    public R<PageResult<WorkspaceVO>> page(WorkspaceQueryRequest request) {
        return R.ok(workspaceService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取工作空间详情")
    public R<WorkspaceVO> getById(@PathVariable String id) {
        return R.ok(workspaceService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新工作空间")
    public R<WorkspaceVO> update(@PathVariable String id,
                                  @Valid @RequestBody WorkspaceUpdateRequest request) {
        return R.ok(workspaceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除工作空间")
    public R<Void> delete(@PathVariable String id) {
        workspaceService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/sync")
    @Operation(summary = "同步工作空间")
    public R<WorkspaceVO> sync(@PathVariable String id) {
        return R.ok(workspaceService.sync(id));
    }
}
