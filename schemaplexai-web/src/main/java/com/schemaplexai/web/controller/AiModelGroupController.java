package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.AiModelGroupAutoGenerateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupCreateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupItemSaveRequest;
import com.schemaplexai.model.dto.system.AiModelGroupUpdateRequest;
import com.schemaplexai.model.vo.system.AiModelGroupVO;
import com.schemaplexai.service.config.AiModelGroupService;
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

import java.util.List;

/**
 * AI模型组管理控制器
 */
@RestController
@RequestMapping("/system/model-groups")
@RequiredArgsConstructor
@Tag(name = "AI模型组管理")
public class AiModelGroupController {

    private final AiModelGroupService aiModelGroupService;

    @GetMapping
    @Operation(summary = "查询当前租户的所有模型组")
    public R<List<AiModelGroupVO>> list() {
        return R.ok(aiModelGroupService.listByCurrentTenant());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模型组详情（含成员列表）")
    public R<AiModelGroupVO> getById(@PathVariable String id) {
        return R.ok(aiModelGroupService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建模型组")
    public R<AiModelGroupVO> create(@Valid @RequestBody AiModelGroupCreateRequest request) {
        return R.ok(aiModelGroupService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新模型组基本信息")
    public R<AiModelGroupVO> update(@PathVariable String id, @RequestBody AiModelGroupUpdateRequest request) {
        return R.ok(aiModelGroupService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模型组")
    public R<Void> delete(@PathVariable String id) {
        aiModelGroupService.delete(id);
        return R.ok();
    }

    @PutMapping("/{id}/items")
    @Operation(summary = "保存模型组成员排序（整体替换）")
    public R<AiModelGroupVO> saveItems(@PathVariable String id, @RequestBody AiModelGroupItemSaveRequest request) {
        return R.ok(aiModelGroupService.saveItems(id, request));
    }

    @PostMapping("/auto-generate")
    @Operation(summary = "按策略自动生成模型组（by_price/by_performance）")
    public R<AiModelGroupVO> autoGenerate(@Valid @RequestBody AiModelGroupAutoGenerateRequest request) {
        return R.ok(aiModelGroupService.autoGenerate(request));
    }
}
