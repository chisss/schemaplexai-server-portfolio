package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workflow.ManualTriggerRequest;
import com.schemaplexai.model.dto.workflow.TriggerConfigUpdateRequest;
import com.schemaplexai.model.vo.workflow.ManualTriggerResultVO;
import com.schemaplexai.model.vo.workflow.TriggerConfigVO;
import com.schemaplexai.model.vo.workflow.TriggerStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.workflow.WorkflowTriggerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流触发管理控制器
 */
@RestController
@RequestMapping("/workflows/triggers")
@RequiredArgsConstructor
@Tag(name = "工作流触发管理")
public class WorkflowTriggerController {

    private final WorkflowTriggerService triggerService;

    @GetMapping("/stats")
    @Operation(summary = "触发统计概览")
    public R<TriggerStatsVO> getStats() {
        return R.ok(triggerService.getStats());
    }

    @GetMapping
    @Operation(summary = "分页查询触发配置列表")
    public R<PageResult<TriggerConfigVO>> pageTriggerConfigs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String triggerType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(triggerService.pageTriggerConfigs(keyword, triggerType, page, size));
    }

    @GetMapping("/{templateId}")
    @Operation(summary = "获取单个模板的触发配置")
    public R<TriggerConfigVO> getTriggerConfig(@PathVariable String templateId) {
        return R.ok(triggerService.getTriggerConfig(templateId));
    }

    @PutMapping("/{templateId}/config")
    @Operation(summary = "更新触发节点配置")
    public R<TriggerConfigVO> updateTriggerConfig(
            @PathVariable String templateId,
            @RequestBody TriggerConfigUpdateRequest request) {
        return R.ok(triggerService.updateTriggerConfig(templateId, request));
    }

    @PutMapping("/{templateId}/toggle")
    @Operation(summary = "启停触发")
    public R<TriggerConfigVO> toggleTrigger(
            @PathVariable String templateId,
            @RequestParam boolean enabled) {
        return R.ok(triggerService.toggleTrigger(templateId, enabled));
    }

    @PostMapping("/{templateId}/fire")
    @Operation(summary = "手动触发工作流")
    public R<ManualTriggerResultVO> fireTrigger(
            @PathVariable String templateId,
            @RequestBody(required = false) ManualTriggerRequest request) {
        if (request == null) {
            request = new ManualTriggerRequest();
        }
        return R.ok(triggerService.fireTrigger(templateId, request));
    }

    @GetMapping("/{templateId}/recent-executions")
    @Operation(summary = "最近执行记录")
    public R<List<WorkflowInstanceVO>> recentExecutions(
            @PathVariable String templateId,
            @RequestParam(defaultValue = "5") int limit) {
        return R.ok(triggerService.recentExecutions(templateId, limit));
    }
}
