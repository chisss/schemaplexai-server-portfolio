package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.skill.SkillCreateRequest;
import com.schemaplexai.model.dto.skill.SkillQueryRequest;
import com.schemaplexai.model.dto.skill.SkillUpdateRequest;
import com.schemaplexai.model.vo.skill.SkillVO;
import com.schemaplexai.service.skill.SkillService;
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
 * Skill管理控制器
 */
@RestController
@RequestMapping("/skills")
@RequiredArgsConstructor
@Tag(name = "Skill管理")
public class SkillController {

    private final SkillService skillService;

    @PostMapping
    @Operation(summary = "创建技能")
    public R<SkillVO> create(@Valid @RequestBody SkillCreateRequest request) {
        return R.ok(skillService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询技能列表")
    public R<PageResult<SkillVO>> page(SkillQueryRequest request) {
        return R.ok(skillService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取技能详情")
    public R<SkillVO> getById(@PathVariable String id) {
        return R.ok(skillService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新技能")
    public R<SkillVO> update(@PathVariable String id,
                             @Valid @RequestBody SkillUpdateRequest request) {
        return R.ok(skillService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除技能")
    public R<Void> delete(@PathVariable String id) {
        skillService.delete(id);
        return R.ok();
    }
}
