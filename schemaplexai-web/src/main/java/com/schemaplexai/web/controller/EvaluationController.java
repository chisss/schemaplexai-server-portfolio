package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.evaluation.EvalDatasetCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetItemSaveRequest;
import com.schemaplexai.model.dto.evaluation.EvalDatasetUpdateRequest;
import com.schemaplexai.model.dto.evaluation.EvalGateConfigSaveRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskCreateRequest;
import com.schemaplexai.model.dto.evaluation.EvalTaskQueryRequest;
import com.schemaplexai.model.vo.evaluation.EvalDatasetTemplateVO;
import com.schemaplexai.model.vo.evaluation.EvalDatasetVO;
import com.schemaplexai.model.vo.evaluation.EvalGateConfigVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskSummaryVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVerdictVO;
import com.schemaplexai.model.vo.evaluation.EvalTaskVO;
import com.schemaplexai.service.evaluation.EvalDatasetService;
import com.schemaplexai.service.evaluation.EvalDatasetTemplateService;
import com.schemaplexai.service.evaluation.EvalGateConfigService;
import com.schemaplexai.service.evaluation.EvalTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
    private final EvalDatasetTemplateService evalDatasetTemplateService;
    private final EvalGateConfigService evalGateConfigService;

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PostMapping("/datasets")
    @Operation(summary = "创建评估数据集")
    public R<EvalDatasetVO> createDataset(@Valid @RequestBody EvalDatasetCreateRequest request) {
        return R.ok(evalDatasetService.create(request));
    }

    @GetMapping("/dataset-templates")
    @Operation(summary = "获取数据集内置模板（RAG 场景化等，从模板创建数据集）")
    public R<List<EvalDatasetTemplateVO>> datasetTemplates() {
        return R.ok(evalDatasetTemplateService.list());
    }

    @GetMapping("/gate-config")
    @Operation(summary = "获取评估门禁配置（后台管理页）")
    public R<EvalGateConfigVO> getGateConfig() {
        return R.ok(evalGateConfigService.get());
    }

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PutMapping("/gate-config")
    @Operation(summary = "保存评估门禁配置（启用/停用 CI 门禁）")
    public R<EvalGateConfigVO> saveGateConfig(@Valid @RequestBody EvalGateConfigSaveRequest request) {
        return R.ok(evalGateConfigService.save(request));
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

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PutMapping("/datasets/{id}")
    @Operation(summary = "更新评估数据集")
    public R<EvalDatasetVO> updateDataset(@PathVariable String id,
                                          @Valid @RequestBody EvalDatasetUpdateRequest request) {
        return R.ok(evalDatasetService.update(id, request));
    }

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PutMapping("/datasets/{id}/items")
    @Operation(summary = "保存评估数据集条目")
    public R<EvalDatasetVO> saveDatasetItems(@PathVariable String id,
                                             @Valid @RequestBody EvalDatasetItemSaveRequest request) {
        return R.ok(evalDatasetService.saveItems(id, request));
    }

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @DeleteMapping("/datasets/{id}")
    @Operation(summary = "删除评估数据集")
    public R<Void> deleteDataset(@PathVariable String id) {
        evalDatasetService.delete(id);
        return R.ok();
    }

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PostMapping("/tasks")
    @Operation(summary = "创建评估任务")
    public R<EvalTaskVO> createTask(@Valid @RequestBody EvalTaskCreateRequest request) {
        return R.ok(evalTaskService.create(request));
    }

    @GetMapping("/tasks")
    @Operation(summary = "分页查询评估任务（概要列表，不含结果明细）")
    public R<PageResult<EvalTaskSummaryVO>> pageTasks(EvalTaskQueryRequest request) {
        return R.ok(evalTaskService.page(request));
    }

    @GetMapping("/tasks/{id}")
    @Operation(summary = "获取评估任务详情")
    public R<EvalTaskVO> getTask(@PathVariable String id) {
        return R.ok(evalTaskService.getById(id));
    }

    @GetMapping("/tasks/{id}/verdict")
    @Operation(summary = "评估任务门禁判定（CI 回归门禁端点；passRateThreshold 默认 100 严格全过，<100 达标率门禁）")
    public R<EvalTaskVerdictVO> verdict(@PathVariable String id,
                                        @RequestParam(required = false) Integer passRateThreshold) {
        return R.ok(evalTaskService.verdict(id, passRateThreshold));
    }

    @PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).EVALUATION_MANAGE.getCode())")
    @PostMapping("/tasks/{id}/run")
    @Operation(summary = "重跑评估任务")
    public R<EvalTaskVO> runTask(@PathVariable String id) {
        return R.ok(evalTaskService.run(id));
    }
}
