package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.evaluation.EvalDatasetCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetItemSaveRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetUpdateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.vo.evaluation.EvalDatasetVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;
import com.schemaplexai.service.evaluation.EvalDatasetService;
import com.schemaplexai.service.evaluation.EvalTaskService;
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

/**
 * 模型评估中心控制器
 */
@RestController
@RequestMapping("/evaluation")
@RequiredArgsConstructor
@Tag(name = "模型评估中心")
public class EvaluationController {

    private final EvalDatasetService evalDatasetService;
    private final EvalTaskService evalTaskService;

    @PostMapping("/datasets")
    @Operation(summary = "创建评估数据集")
    public R<EvalDatasetVO> createDataset(@Valid @RequestBody EvalDatasetCreateRequest request) {
        return R.ok(evalDatasetService.create(request));
    }

    @GetMapping("/datasets")
    @Operation(summary = "分页查询评估数据集")
    public R<PageResult<EvalDatasetVO>> pageDatasets(@RequestParam(defaultValue = "1") Integer page,
                                                     @RequestParam(defaultValue = "20") Integer size,
                                                     @RequestParam(required = false) String keyword) {
        return R.ok(evalDatasetService.page(page, size, keyword));
    }

    @GetMapping("/datasets/{id}")
    @Operation(summary = "获取评估数据集详情")
    public R<EvalDatasetVO> getDataset(@PathVariable String id) {
        return R.ok(evalDatasetService.getById(id));
    }

    @PutMapping("/datasets/{id}")
    @Operation(summary = "更新评估数据集")
    public R<EvalDatasetVO> updateDataset(@PathVariable String id,
                                          @Valid @RequestBody EvalDatasetUpdateRequest request) {
        return R.ok(evalDatasetService.update(id, request));
    }

    @PutMapping("/datasets/{id}/items")
    @Operation(summary = "保存评估数据集条目")
    public R<EvalDatasetVO> saveDatasetItems(@PathVariable String id,
                                             @Valid @RequestBody EvalDatasetItemSaveRequest request) {
        return R.ok(evalDatasetService.saveItems(id, request));
    }

    @DeleteMapping("/datasets/{id}")
    @Operation(summary = "删除评估数据集")
    public R<Void> deleteDataset(@PathVariable String id) {
        evalDatasetService.delete(id);
        return R.ok();
    }

    @PostMapping("/tasks")
    @Operation(summary = "创建评估任务")
    public R<EvalTaskVO> createTask(@Valid @RequestBody EvalTaskCreateRequest request) {
        return R.ok(evalTaskService.create(request));
    }

    @GetMapping("/tasks")
    @Operation(summary = "分页查询评估任务")
    public R<PageResult<EvalTaskVO>> pageTasks(EvalTaskQueryRequest request) {
        return R.ok(evalTaskService.page(request));
    }

    @GetMapping("/tasks/{id}")
    @Operation(summary = "获取评估任务详情")
    public R<EvalTaskVO> getTask(@PathVariable String id) {
        return R.ok(evalTaskService.getById(id));
    }

    @PostMapping("/tasks/{id}/run")
    @Operation(summary = "重跑评估任务")
    public R<EvalTaskVO> runTask(@PathVariable String id) {
        return R.ok(evalTaskService.run(id));
    }
}
