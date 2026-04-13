package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workflow.*;
import com.schemaplexai.model.vo.workflow.WorkflowAiArrangeVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.model.vo.workflow.WorkflowNodeExecutionVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 工作流管理控制器
 */
@RestController
@RequestMapping("/workflows")
@RequiredArgsConstructor
@Tag(name = "工作流管理")
public class WorkflowController {

    private final WorkflowTemplateService templateService;
    private final WorkflowInstanceService instanceService;

    // ===== 模板管理 =====

    @GetMapping("/templates/stats")
    @Operation(summary = "获取工作流模板统计数据")
    public R<WorkflowTemplateStatsVO> getTemplateStats() {
        return R.ok(templateService.getStats());
    }

    @PostMapping("/templates")
    @Operation(summary = "创建工作流模板")
    public R<WorkflowTemplateVO> createTemplate(@Valid @RequestBody WorkflowTemplateCreateRequest request) {
        return R.ok(templateService.create(request));
    }

    @GetMapping("/templates")
    @Operation(summary = "分页查询工作流模板")
    public R<PageResult<WorkflowTemplateVO>> pageTemplates(WorkflowTemplateQueryRequest request) {
        return R.ok(templateService.page(request));
    }

    @GetMapping("/templates/{id}")
    @Operation(summary = "获取工作流模板详情")
    public R<WorkflowTemplateVO> getTemplate(@PathVariable String id) {
        return R.ok(templateService.getById(id));
    }

    @PutMapping("/templates/{id}")
    @Operation(summary = "更新工作流模板")
    public R<WorkflowTemplateVO> updateTemplate(@PathVariable String id,
                                                 @Valid @RequestBody WorkflowTemplateUpdateRequest request) {
        return R.ok(templateService.update(id, request));
    }

    @DeleteMapping("/templates/{id}")
    @Operation(summary = "删除工作流模板")
    public R<Void> deleteTemplate(@PathVariable String id) {
        templateService.delete(id);
        return R.ok();
    }

    @PostMapping("/templates/{id}/ai-arrange")
    @Operation(summary = "AI自动编排工作流节点")
    public R<WorkflowAiArrangeVO> aiArrange(@PathVariable String id,
                                             @Valid @RequestBody WorkflowAiArrangeRequest request) {
        return R.ok(templateService.aiArrange(id, request));
    }

    @PutMapping("/templates/{id}/toggle-status")
    @Operation(summary = "切换模板启用/停用状态")
    public R<WorkflowTemplateVO> toggleTemplateStatus(@PathVariable String id) {
        return R.ok(templateService.toggleStatus(id));
    }

    // ===== 实例管理 =====

    @PostMapping("/instances")
    @Operation(summary = "创建工作流实例")
    public R<WorkflowInstanceVO> createInstance(@Valid @RequestBody WorkflowInstanceCreateRequest request) {
        return R.ok(instanceService.create(request));
    }

    @GetMapping("/instances")
    @Operation(summary = "分页查询工作流实例")
    public R<PageResult<WorkflowInstanceVO>> pageInstances(WorkflowInstanceQueryRequest request) {
        return R.ok(instanceService.page(request));
    }

    @GetMapping("/instances/{id}")
    @Operation(summary = "获取工作流实例详情")
    public R<WorkflowInstanceVO> getInstance(@PathVariable String id) {
        return R.ok(instanceService.getById(id));
    }

    @PostMapping("/instances/{id}/start")
    @Operation(summary = "启动工作流实例")
    public R<WorkflowInstanceVO> start(@PathVariable String id) {
        return R.ok(instanceService.start(id));
    }

    @PostMapping("/instances/{id}/pause")
    @Operation(summary = "暂停工作流实例")
    public R<WorkflowInstanceVO> pause(@PathVariable String id) {
        return R.ok(instanceService.pause(id));
    }

    @PostMapping("/instances/{id}/resume")
    @Operation(summary = "恢复工作流实例")
    public R<WorkflowInstanceVO> resume(@PathVariable String id) {
        return R.ok(instanceService.resume(id));
    }

    @PostMapping("/instances/{id}/terminate")
    @Operation(summary = "终止工作流实例")
    public R<Void> terminate(@PathVariable String id) {
        instanceService.terminate(id);
        return R.ok();
    }

    // ===== 节点执行 =====

    @GetMapping("/instances/{id}/nodes")
    @Operation(summary = "获取节点执行记录")
    public R<List<WorkflowNodeExecutionVO>> getNodeExecutions(@PathVariable String id) {
        return R.ok(instanceService.getNodeExecutions(id));
    }

    @PostMapping("/instances/{id}/nodes/{nodeId}/approve")
    @Operation(summary = "审批通过节点")
    public R<Void> approveNode(@PathVariable String id,
                                @PathVariable String nodeId,
                                @RequestParam(defaultValue = "") String comment) {
        instanceService.approveNode(id, nodeId, comment);
        return R.ok();
    }

    @PostMapping("/instances/{id}/nodes/{nodeId}/reject")
    @Operation(summary = "审批拒绝节点")
    public R<Void> rejectNode(@PathVariable String id,
                               @PathVariable String nodeId,
                               @RequestParam(defaultValue = "") String comment,
                               @RequestParam(required = false) String rollbackToNodeId) {
        instanceService.rejectNode(id, nodeId, comment, rollbackToNodeId);
        return R.ok();
    }

    @PostMapping("/instances/{id}/nodes/{nodeId}/modify")
    @Operation(summary = "请求修改节点")
    public R<Void> requestModify(@PathVariable String id,
                                  @PathVariable String nodeId,
                                  @RequestParam String modifyInstruction) {
        instanceService.requestModify(id, nodeId, modifyInstruction);
        return R.ok();
    }
}
