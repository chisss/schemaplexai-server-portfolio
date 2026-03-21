package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.dto.spec.SpecDiffRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.dto.spec.SpecQueryRequest;
import com.schemaplexai.model.dto.spec.SpecUpdateRequest;
import com.schemaplexai.model.vo.spec.SpecDiffVO;
import com.schemaplexai.model.vo.spec.SpecDocumentVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.model.vo.spec.SpecVersionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.spec.SpecService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Spec管理控制器
 */
@RestController
@RequestMapping("/specs")
@RequiredArgsConstructor
@Tag(name = "Spec管理")
public class SpecController {

    private final SpecService specService;

    @GetMapping
    @Operation(summary = "分页查询Spec列表")
    public R<PageResult<SpecVO>> list(SpecQueryRequest request) {
        return R.ok(specService.listSpecs(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取Spec详情（含文档）")
    public R<SpecVO> getById(@PathVariable String id) {
        return R.ok(specService.getSpecById(id));
    }

    @PostMapping
    @Operation(summary = "创建Spec")
    public R<SpecVO> create(@Valid @RequestBody SpecCreateRequest request) {
        return R.ok(specService.createSpec(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新Spec基本信息")
    public R<SpecVO> update(@PathVariable String id, @Valid @RequestBody SpecUpdateRequest request) {
        return R.ok(specService.updateSpec(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除Spec")
    public R<Void> delete(@PathVariable String id) {
        specService.deleteSpec(id);
        return R.ok();
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交审批")
    public R<Void> submitForReview(@PathVariable String id, @RequestParam String docType) {
        specService.submitForReview(id, docType);
        return R.ok();
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "审批通过")
    public R<Void> approve(@PathVariable String id) {
        specService.approve(id);
        return R.ok();
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "审批驳回")
    public R<Void> reject(@PathVariable String id) {
        specService.reject(id);
        return R.ok();
    }

    @GetMapping("/{id}/documents/{docType}")
    @Operation(summary = "获取Spec文档")
    public R<SpecDocumentVO> getDocument(@PathVariable String id, @PathVariable String docType) {
        return R.ok(specService.getDocument(id, docType));
    }

    @PutMapping("/{id}/documents/{docType}")
    @Operation(summary = "保存/更新Spec文档")
    public R<SpecDocumentVO> saveDocument(@PathVariable String id, @PathVariable String docType,
                                          @Valid @RequestBody SpecDocumentRequest request) {
        return R.ok(specService.saveDocument(id, docType, request));
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "获取文档版本历史")
    public R<List<SpecVersionVO>> getVersionHistory(@PathVariable String id,
                                                     @RequestParam String docType) {
        return R.ok(specService.getVersionHistory(id, docType));
    }

    @GetMapping("/{id}/versions/{versionId}")
    @Operation(summary = "获取特定版本详情")
    public R<SpecVersionVO> getVersionById(@PathVariable String id,
                                            @PathVariable String versionId) {
        return R.ok(specService.getVersionById(id, versionId));
    }

    @PostMapping("/{id}/versions/diff")
    @Operation(summary = "版本Diff对比")
    public R<SpecDiffVO> diffVersions(@PathVariable String id,
                                       @Valid @RequestBody SpecDiffRequest request) {
        return R.ok(specService.diffVersions(id, request));
    }

    @GetMapping("/{id}/workflow-tracking")
    @Operation(summary = "获取Spec关联的工作流追踪信息（最新实例）")
    public R<WorkflowInstanceVO> getWorkflowTracking(@PathVariable String id) {
        return R.ok(specService.getWorkflowTracking(id));
    }
}
