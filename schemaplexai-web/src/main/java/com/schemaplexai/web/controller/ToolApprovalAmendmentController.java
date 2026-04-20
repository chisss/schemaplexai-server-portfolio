package com.schemaplexai.web.controller;

import com.schemaplexai.common.enums.AmendmentScopeEnum;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.agent.AmendmentCreateRequest;
import com.schemaplexai.model.entity.ToolApprovalAmendment;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具审批修正规则控制器（渐进式信任）
 */
@RestController
@RequestMapping("/amendments")
@RequiredArgsConstructor
@Tag(name = "工具审批修正规则")
public class ToolApprovalAmendmentController {

    private final ToolApprovalAmendmentService amendmentService;

    @GetMapping
    @Operation(summary = "查询Agent的修正规则列表")
    public R<List<ToolApprovalAmendment>> list(@RequestParam String agentId) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        return R.ok(amendmentService.listAmendments(tenantId, agentId));
    }

    @PostMapping
    @Operation(summary = "创建修正规则")
    public R<ToolApprovalAmendment> create(@Valid @RequestBody AmendmentCreateRequest request) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        String userId = SecurityUtil.getCurrentUserId();
        AmendmentScopeEnum scope = AmendmentScopeEnum.valueOf(
                request.getScope() != null ? request.getScope() : "AGENT");
        ToolApprovalAmendment amendment = amendmentService.createAmendment(
                tenantId, request.getAgentId(), request.getToolCode(),
                request.getCommand(), userId, scope, request.getExpiresAt());
        return R.ok(amendment);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除修正规则")
    public R<Void> delete(@PathVariable String id) {
        amendmentService.deleteAmendment(id);
        return R.ok();
    }
}
