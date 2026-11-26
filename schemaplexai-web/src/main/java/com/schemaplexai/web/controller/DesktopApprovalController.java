package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.service.desktop.DesktopApprovalService;
import com.schemaplexai.web.util.RequestContextUtils;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 员工侧桌面自助入口。
 *
 * <p>入口只声明「登录 + 个人自助权限」；真正的资源级授权在 service：
 * 所有查询按 {@code Employee.current()} 的租户与用户过滤，跨用户资源一律不可见。
 * 把细粒度判定留在服务层，是因为同一条端点常按业务类型对应不同权限码（如审批决策），
 * 注解无法表达，硬写只会与 service 口径漂移。
 */
@Validated
@PreAuthorize("@authorizationService.hasPermission('self:service')")
@RestController
@RequiredArgsConstructor
@RequestMapping("/desktop/approvals")
public class DesktopApprovalController {
    private final DesktopApprovalService approvals;

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "true") boolean pending, @RequestParam(required = false) String type,
                     @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(approvals.list(pending, type, page, size));
    }

    @GetMapping("/{type}/{id}")
    public R<?> detail(@PathVariable String type, @PathVariable String id) {
        return R.ok(approvals.detail(type, id));
    }

    @PostMapping("/{type}/{id}/decisions")
    public R<?> decide(@PathVariable String type, @PathVariable String id,
                       @Valid @RequestBody DesktopApprovalService.Decision decision,
                       HttpServletRequest request) {
        SecurityAuditContext auditContext = RequestContextUtils.buildSecurityAuditContext(request);
        return R.ok(approvals.decide(type, id, decision, auditContext));
    }
}
