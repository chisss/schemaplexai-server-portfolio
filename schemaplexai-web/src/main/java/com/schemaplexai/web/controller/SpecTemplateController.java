package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.spec.SpecFromTemplateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateCreateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateQueryRequest;
import com.schemaplexai.model.vo.spec.SpecTemplateVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.service.spec.SpecTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Spec模板管理控制器
 */
@RestController
@RequestMapping("/spec-templates")
@RequiredArgsConstructor
@Tag(name = "Spec模板管理")
public class SpecTemplateController {

    private final SpecTemplateService specTemplateService;

    @GetMapping
    @Operation(summary = "分页查询模板列表")
    public R<PageResult<SpecTemplateVO>> list(SpecTemplateQueryRequest request) {
        return R.ok(specTemplateService.listTemplates(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取模板详情")
    public R<SpecTemplateVO> getById(@PathVariable String id) {
        return R.ok(specTemplateService.getTemplateById(id));
    }

    @PostMapping
    @Operation(summary = "创建自定义模板")
    public R<SpecTemplateVO> create(@Valid @RequestBody SpecTemplateCreateRequest request) {
        return R.ok(specTemplateService.createTemplate(request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除模板（内置模板不可删除）")
    public R<Void> delete(@PathVariable String id) {
        specTemplateService.deleteTemplate(id);
        return R.ok();
    }

    @PostMapping("/{id}/create-spec")
    @Operation(summary = "从模板创建Spec")
    public R<SpecVO> createSpecFromTemplate(@PathVariable String id,
                                             @Valid @RequestBody SpecFromTemplateRequest request) {
        return R.ok(specTemplateService.createSpecFromTemplate(id, request));
    }
}
