package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.RoleCreateRequest;
import com.schemaplexai.model.dto.system.RoleQueryRequest;
import com.schemaplexai.model.dto.system.RoleUpdateRequest;
import com.schemaplexai.model.dto.system.StatusUpdateRequest;
import com.schemaplexai.model.vo.system.RoleVO;
import com.schemaplexai.service.config.RoleService;
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

import java.util.List;

/**
 * 角色管理控制器
 */
@RestController
@RequestMapping("/system/roles")
@RequiredArgsConstructor
@Tag(name = "角色管理")
public class RoleController {

    private final RoleService roleService;

    @GetMapping
    @Operation(summary = "分页查询角色列表")
    public R<PageResult<RoleVO>> list(RoleQueryRequest request) {
        return R.ok(roleService.listRoles(request));
    }

    @GetMapping("/all")
    @Operation(summary = "查询所有角色（不分页）")
    public R<List<RoleVO>> listAll() {
        return R.ok(roleService.listAllRoles());
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取角色详情")
    public R<RoleVO> getById(@PathVariable String id) {
        return R.ok(roleService.getRoleById(id));
    }

    @PostMapping
    @Operation(summary = "创建角色")
    public R<RoleVO> create(@Valid @RequestBody RoleCreateRequest request) {
        return R.ok(roleService.createRole(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新角色")
    public R<RoleVO> update(@PathVariable String id, @Valid @RequestBody RoleUpdateRequest request) {
        return R.ok(roleService.updateRole(id, request));
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "更新角色状态")
    public R<Void> updateStatus(@PathVariable String id, @Valid @RequestBody StatusUpdateRequest request) {
        roleService.updateStatus(id, request.getStatus());
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除角色")
    public R<Void> delete(@PathVariable String id) {
        roleService.deleteRole(id);
        return R.ok();
    }
}
