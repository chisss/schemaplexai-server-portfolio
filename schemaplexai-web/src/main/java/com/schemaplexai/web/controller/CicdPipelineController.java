package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.cicd.CicdPipelineCreateRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineQueryRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineUpdateRequest;
import com.schemaplexai.model.vo.cicd.CicdPipelineVO;
import com.schemaplexai.service.cicd.CicdPipelineService;
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

import java.util.Map;

/**
 * CICD Pipeline管理控制器
 */
@RestController
@RequestMapping("/cicd-pipelines")
@RequiredArgsConstructor
@Tag(name = "CICD Pipeline管理")
public class CicdPipelineController {

    private final CicdPipelineService pipelineService;

    @PostMapping
    @Operation(summary = "创建Pipeline配置")
    public R<CicdPipelineVO> create(@Valid @RequestBody CicdPipelineCreateRequest request) {
        return R.ok(pipelineService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询Pipeline列表")
    public R<PageResult<CicdPipelineVO>> page(CicdPipelineQueryRequest request) {
        return R.ok(pipelineService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取Pipeline详情")
    public R<CicdPipelineVO> getById(@PathVariable String id) {
        return R.ok(pipelineService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新Pipeline")
    public R<CicdPipelineVO> update(@PathVariable String id,
                                     @Valid @RequestBody CicdPipelineUpdateRequest request) {
        return R.ok(pipelineService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除Pipeline")
    public R<Void> delete(@PathVariable String id) {
        pipelineService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/trigger")
    @Operation(summary = "触发构建")
    public R<Map<String, Object>> trigger(@PathVariable String id) {
        return R.ok(pipelineService.trigger(id));
    }
}
