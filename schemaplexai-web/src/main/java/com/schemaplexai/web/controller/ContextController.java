package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.context.*;
import com.schemaplexai.model.vo.context.*;
import com.schemaplexai.service.context.ContextService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 上下文管理控制器
 */
@RestController
@RequestMapping("/contexts")
@RequiredArgsConstructor
@Tag(name = "上下文管理")
public class ContextController {

    private final ContextService contextService;

    // ===== 上下文 CRUD =====

    @PostMapping
    @Operation(summary = "创建上下文")
    public R<ContextVO> create(@Valid @RequestBody ContextCreateRequest request) {
        return R.ok(contextService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询上下文列表")
    public R<PageResult<ContextVO>> page(ContextQueryRequest request) {
        return R.ok(contextService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取上下文详情（含条目列表）")
    public R<ContextDetailVO> getById(@PathVariable String id) {
        return R.ok(contextService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新上下文")
    public R<ContextVO> update(@PathVariable String id,
                               @Valid @RequestBody ContextUpdateRequest request) {
        return R.ok(contextService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除上下文（级联删除条目和快照）")
    public R<Void> delete(@PathVariable String id) {
        contextService.delete(id);
        return R.ok();
    }

    // ===== 条目管理 =====

    @PostMapping("/{id}/items")
    @Operation(summary = "添加上下文条目")
    public R<ContextItemVO> addItem(@PathVariable String id,
                                    @Valid @RequestBody ContextItemCreateRequest request) {
        return R.ok(contextService.addItem(id, request));
    }

    @GetMapping("/{id}/items")
    @Operation(summary = "查询上下文条目列表")
    public R<List<ContextItemVO>> listItems(@PathVariable String id,
                                            @RequestParam(required = false) String itemType) {
        return R.ok(contextService.listItems(id, itemType));
    }

    @PutMapping("/{id}/items/{itemId}")
    @Operation(summary = "更新上下文条目")
    public R<ContextItemVO> updateItem(@PathVariable String id,
                                       @PathVariable String itemId,
                                       @Valid @RequestBody ContextItemUpdateRequest request) {
        return R.ok(contextService.updateItem(id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @Operation(summary = "删除上下文条目")
    public R<Void> deleteItem(@PathVariable String id, @PathVariable String itemId) {
        contextService.deleteItem(id, itemId);
        return R.ok();
    }

    // ===== 快照管理 =====

    @GetMapping("/{id}/snapshots")
    @Operation(summary = "查询上下文快照列表")
    public R<List<ContextSnapshotVO>> listSnapshots(@PathVariable String id) {
        return R.ok(contextService.listSnapshots(id));
    }

    @PostMapping("/{id}/snapshots")
    @Operation(summary = "创建上下文快照")
    public R<ContextSnapshotVO> createSnapshot(@PathVariable String id,
                                               @RequestParam(defaultValue = "") String snapshotName) {
        return R.ok(contextService.createSnapshot(id, snapshotName));
    }

    @GetMapping("/{id}/snapshots/{snapId}")
    @Operation(summary = "获取快照详情")
    public R<ContextSnapshotVO> getSnapshotById(@PathVariable String id,
                                                 @PathVariable String snapId) {
        return R.ok(contextService.getSnapshotById(id, snapId));
    }

    @PostMapping("/{id}/snapshots/{snapId}/restore")
    @Operation(summary = "恢复上下文快照")
    public R<Map<String, Object>> restoreSnapshot(@PathVariable String id,
                                                   @PathVariable String snapId) {
        return R.ok(contextService.restoreSnapshot(id, snapId));
    }

    // ===== 上下文解析（核心） =====

    @PostMapping("/resolve")
    @Operation(summary = "上下文解析 - 为Agent执行任务组装四层上下文")
    public R<ContextResolvedVO> resolve(@RequestBody ContextResolveRequest request) {
        return R.ok(contextService.resolve(request));
    }

    // ===== 关联关系管理 =====

    @GetMapping("/{id}/relations")
    @Operation(summary = "查询上下文关联关系（用于知识图谱连线）")
    public R<List<com.schemaplexai.model.vo.context.ContextRelationVO>> listRelations(@PathVariable String id) {
        return R.ok(contextService.listRelations(id));
    }
}