package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchAssignRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterBatchReviewRequest;
import com.schemaplexai.model.dto.approval.ApprovalCenterQueryRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.web.util.RequestContextUtils;
import com.schemaplexai.model.vo.approval.ApprovalCenterBatchActionResultVO;
import com.schemaplexai.model.vo.approval.ApprovalCenterItemVO;
import com.schemaplexai.service.common.PermissionLoader;
import com.schemaplexai.service.approval.ApprovalCenterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 统一审批中心控制器
 */
@RestController
@RequestMapping("/approval-center")
@RequiredArgsConstructor
@Tag(name = "统一审批中心")
public class ApprovalCenterController {

    private final ApprovalCenterService approvalCenterService;
    private final PermissionLoader permissionLoader;

    @GetMapping("/items")
    @Operation(summary = "分页查询统一审批项")
    public R<PageResult<ApprovalCenterItemVO>> pageItems(ApprovalCenterQueryRequest request) {
        assertPermission("approval:center:view");
        return R.ok(approvalCenterService.page(request));
    }

    @PostMapping("/batch-review")
    @Operation(summary = "批量审批工作流审批项")
    public R<ApprovalCenterBatchActionResultVO> batchReview(@Valid @RequestBody ApprovalCenterBatchReviewRequest request) {
        assertPermission("approval:center:review");
        return R.ok(approvalCenterService.batchReview(request));
    }

    @PostMapping("/batch-assign")
    @Operation(summary = "批量指派审批项")
    public R<ApprovalCenterBatchActionResultVO> batchAssign(@Valid @RequestBody ApprovalCenterBatchAssignRequest request,
                                                            HttpServletRequest httpRequest) {
        assertPermission("approval:center:assign");
        return R.ok(approvalCenterService.batchAssign(request, RequestContextUtils.buildSecurityAuditContext(httpRequest)));
    }

    @GetMapping("/audit/export")
    @Operation(summary = "导出审批审计记录")
    public ResponseEntity<ByteArrayResource> exportAudit(ApprovalCenterQueryRequest request) {
        assertPermission("approval:center:export");
        byte[] content = approvalCenterService.exportAudit(request);
        String fileName = approvalCenterService.resolveExportFileName();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(new ByteArrayResource(content));
    }

    private void assertPermission(String permissionCode) {
        if (hasRole("SUPER_ADMIN")) {
            return;
        }
        String userId = SecurityUtil.getCurrentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new AccessDeniedException("无操作权限");
        }
        Set<String> permissionCodes = permissionLoader.loadPermissionCodeSet(userId);
        if (!permissionCodes.contains(permissionCode)) {
            throw new AccessDeniedException("无操作权限");
        }
    }

    private boolean hasRole(String roleCode) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + roleCode).equals(authority.getAuthority()));
    }
}
