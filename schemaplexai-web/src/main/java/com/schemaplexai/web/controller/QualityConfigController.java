package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.quality.QualityProfileConfigRequest;
import com.schemaplexai.model.vo.quality.QualityProfileConfigVO;
import com.schemaplexai.service.quality.QualityProfileFacadeService;
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
 * 质量配置组控制器
 */
@RestController
@RequestMapping("/quality/config-profiles")
@RequiredArgsConstructor
@Tag(name = "质量配置组")
public class QualityConfigController {

    private final QualityProfileFacadeService qualityProfileFacadeService;

    @GetMapping
    @Operation(summary = "查询质量配置组")
    public R<List<QualityProfileConfigVO>> list() {
        return R.ok(qualityProfileFacadeService.list());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取质量配置组详情")
    public R<QualityProfileConfigVO> getById(@PathVariable String id) {
        return R.ok(qualityProfileFacadeService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建质量配置组")
    public R<QualityProfileConfigVO> create(@Valid @RequestBody QualityProfileConfigRequest request) {
        return R.ok(qualityProfileFacadeService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新质量配置组")
    public R<QualityProfileConfigVO> update(@PathVariable String id,
                                            @Valid @RequestBody QualityProfileConfigRequest request) {
        return R.ok(qualityProfileFacadeService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除质量配置组")
    public R<Void> delete(@PathVariable String id) {
        qualityProfileFacadeService.delete(id);
        return R.ok();
    }
}
