package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.StatusUpdateRequest;
import com.schemaplexai.model.dto.system.TenantCreateRequest;
import com.schemaplexai.model.dto.system.TenantProfileUpdateRequest;
import com.schemaplexai.model.dto.system.TenantUpdateRequest;
import com.schemaplexai.model.vo.system.TenantVO;
import com.schemaplexai.service.config.TenantService;
import com.schemaplexai.service.config.TenantTemplateService;
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
 * 租户管理控制器
 */
@RestController
@RequestMapping("/system/tenants")
@RequiredArgsConstructor
@Tag(name = "租户管理")
public class TenantController {

    private final TenantService tenantService;
    private final TenantTemplateService tenantTemplateService;

    @GetMapping
    @Operation(summary = "分页查询租户列表")
    public R<PageResult<TenantVO>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return R.ok(tenantService.listTenants(page, size, keyword));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取租户详情")
    public R<TenantVO> getById(@PathVariable String id) {
        return R.ok(tenantService.getTenantById(id));
    }

    @PostMapping
    @Operation(summary = "创建租户")
    public R<TenantVO> create(@Valid @RequestBody TenantCreateRequest request) {
        return R.ok(tenantService.createTenant(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新租户")
    public R<TenantVO> update(@PathVariable String id, @Valid @RequestBody TenantUpdateRequest request) {
        return R.ok(tenantService.updateTenant(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新租户状态")
    public R<Void> updateStatus(@PathVariable String id, @Valid @RequestBody StatusUpdateRequest request) {
        tenantService.updateStatus(id, request.getStatus());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除租户")
    public R<Void> delete(@PathVariable String id) {
        tenantService.deleteTenant(id);
        return R.ok();
    }

    @PutMapping("/{id}/profile")
    @Operation(summary = "更新租户行业配置（行业、场景、能力）")
    public R<TenantVO> updateProfile(@PathVariable String id, @RequestBody TenantProfileUpdateRequest request) {
        return R.ok(tenantTemplateService.updateProfile(id, request));
    }

    @PostMapping("/{id}/init-template")
    @Operation(summary = "触发租户行业模板初始化（异步）")
    public R<String> initTemplate(@PathVariable String id) {
        return R.ok(tenantTemplateService.initializeTemplate(id));
    }

    @GetMapping("/{id}/init-status")
    @Operation(summary = "查询租户模板初始化状态（pending/running/done/failed）")
    public R<String> getInitStatus(@PathVariable String id) {
        return R.ok(tenantTemplateService.getInitStatus(id));
    }
}
