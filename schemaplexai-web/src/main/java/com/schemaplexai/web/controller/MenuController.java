package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.system.MenuCreateRequest;
import com.schemaplexai.model.dto.system.MenuUpdateRequest;
import com.schemaplexai.model.vo.system.MenuVO;
import com.schemaplexai.service.config.MenuService;
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
 * 菜单管理控制器
 */
@RestController
@RequestMapping("/system/menus")
@RequiredArgsConstructor
@Tag(name = "菜单管理")
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/tree")
    @Operation(summary = "获取完整菜单树")
    public R<List<MenuVO>> getMenuTree() {
        return R.ok(menuService.getMenuTree());
    }

    @GetMapping("/user-tree")
    @Operation(summary = "获取当前用户菜单树")
    public R<List<MenuVO>> getCurrentUserMenuTree() {
        String userId = SecurityUtil.getCurrentUserId();
        return R.ok(menuService.getCurrentUserMenuTree(userId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取菜单详情")
    public R<MenuVO> getById(@PathVariable String id) {
        return R.ok(menuService.getMenuById(id));
    }

    @PostMapping
    @Operation(summary = "创建菜单")
    public R<MenuVO> create(@Valid @RequestBody MenuCreateRequest request) {
        return R.ok(menuService.createMenu(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新菜单")
    public R<MenuVO> update(@PathVariable String id, @Valid @RequestBody MenuUpdateRequest request) {
        return R.ok(menuService.updateMenu(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除菜单")
    public R<Void> delete(@PathVariable String id) {
        menuService.deleteMenu(id);
        return R.ok();
    }
}
