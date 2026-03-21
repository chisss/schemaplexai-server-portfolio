package com.schemaplexai.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.result.R;
import com.schemaplexai.dao.mapper.PermissionMapper;
import com.schemaplexai.model.converter.PermissionConverter;
import com.schemaplexai.model.entity.Permission;
import com.schemaplexai.model.vo.system.PermissionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理控制器
 */
@RestController
@RequestMapping("/system/permissions")
@RequiredArgsConstructor
@Tag(name = "权限管理")
public class PermissionController {

    private final PermissionMapper permissionMapper;
    private final PermissionConverter permissionConverter;

    @GetMapping
    @Operation(summary = "查询所有权限列表（按 sortOrder 排序）")
    public R<List<PermissionVO>> listAll() {
        var wrapper = new LambdaQueryWrapper<Permission>()
                .orderByAsc(Permission::getSortOrder);
        var permissions = permissionMapper.selectList(wrapper);
        return R.ok(permissionConverter.toVOList(permissions));
    }
}
