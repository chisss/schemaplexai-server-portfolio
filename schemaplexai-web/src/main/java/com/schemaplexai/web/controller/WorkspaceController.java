package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workspace.WorkspaceCreateRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceQueryRequest;
import com.schemaplexai.model.dto.workspace.WorkspaceUpdateRequest;
import com.schemaplexai.model.vo.artifact.ArtifactVO;
import com.schemaplexai.model.vo.workspace.WorkspaceFileContentVO;
import com.schemaplexai.model.vo.workspace.WorkspaceFileVO;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.workspace.WorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

    @GetMapping("/all")
    @Operation(summary = "获取所有工作空间（用于上下文关联项目下拉）")
    public R<List<WorkspaceVO>> listAll() {
        return R.ok(workspaceService.listAll());
    }

    @GetMapping("/{id}/branches")
    @Operation(summary = "列出工作空间的所有分支")
    public R<List<GitOperationService.BranchInfo>> listBranches(@PathVariable String id) {
        return R.ok(workspaceService.listBranches(id));
    }

    @GetMapping("/{id}/branches/current")
    @Operation(summary = "获取当前分支")
    public R<String> getCurrentBranch(@PathVariable String id) {
        return R.ok(workspaceService.getCurrentBranch(id));
    }

    @PostMapping("/{id}/branches")
    @Operation(summary = "创建分支")
    public R<GitOperationService.BranchInfo> createBranch(
            @PathVariable String id,
            @RequestParam String branchName,
            @RequestParam(required = false) String startPoint) {
        return R.ok(workspaceService.createBranch(id, branchName, startPoint));
    }

    @GetMapping("/{id}/files")
    @Operation(summary = "列出工作空间文件")
    public R<List<WorkspaceFileVO>> listFiles(@PathVariable String id,
                                              @RequestParam(required = false) String path) {
        return R.ok(workspaceService.listFiles(id, path));
    }

    @GetMapping("/{id}/files/content")
    @Operation(summary = "读取工作空间文件内容")
    public R<WorkspaceFileContentVO> readFile(@PathVariable String id,
                                              @RequestParam String path) {
        return R.ok(workspaceService.readFile(id, path));
    }

    @GetMapping("/{id}/files/download")
    @Operation(summary = "下载工作空间文件")
    public ResponseEntity<ByteArrayResource> downloadFile(@PathVariable String id,
                                                          @RequestParam String path) {
        WorkspaceService.WorkspaceFileDownload payload = workspaceService.downloadFile(id, path);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(payload.mimeType()))
                .body(new ByteArrayResource(payload.content()));
    }

    @GetMapping("/{id}/artifacts")
    @Operation(summary = "获取工作空间关联产物")
    public R<List<ArtifactVO>> listArtifacts(@PathVariable String id) {
        return R.ok(workspaceService.listArtifacts(id));
    }
}
