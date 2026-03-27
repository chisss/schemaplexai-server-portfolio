package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.TenantRuntimePolicyUpdateRequest;
import com.schemaplexai.model.vo.system.TenantRuntimePolicyVO;
import com.schemaplexai.service.config.TenantRuntimePolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户运行时策略控制器
 */
@RestController
@RequestMapping("/system/tenants/{tenantId}/runtime-policy")
@RequiredArgsConstructor
@Tag(name = "租户运行时策略")
public class TenantRuntimePolicyController {

    private final TenantRuntimePolicyService tenantRuntimePolicyService;

    @GetMapping
    @Operation(summary = "获取租户运行时策略")
    public R<TenantRuntimePolicyVO> getByTenantId(@PathVariable String tenantId) {
        return R.ok(tenantRuntimePolicyService.getByTenantId(tenantId));
    }

    @PutMapping
    @Operation(summary = "更新租户运行时策略")
    public R<TenantRuntimePolicyVO> update(@PathVariable String tenantId,
                                           @Valid @RequestBody TenantRuntimePolicyUpdateRequest request) {
        return R.ok(tenantRuntimePolicyService.update(tenantId, request));
    }
}
