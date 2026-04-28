package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.user.UserMemoryCreateRequest;
import com.schemaplexai.model.dto.user.UserMemoryQueryRequest;
import com.schemaplexai.model.dto.user.UserMemoryUpdateRequest;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.service.user.UserMemoryService;
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
 * 当前用户记忆管理控制器
 */
@RestController
@RequestMapping("/user-memories")
@RequiredArgsConstructor
@Tag(name = "用户记忆管理")
public class UserMemoryController {

    private final UserMemoryService userMemoryService;

    @GetMapping
    @Operation(summary = "查询当前用户记忆列表")
    public R<List<UserMemory>> list(UserMemoryQueryRequest request) {
        return R.ok(userMemoryService.list(currentTenantId(), currentUserId(), request));
    }

    @PostMapping
    @Operation(summary = "创建当前用户记忆")
    public R<UserMemory> create(@Valid @RequestBody UserMemoryCreateRequest request) {
        return R.ok(userMemoryService.createExplicitMemory(currentTenantId(), currentUserId(), request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新当前用户记忆")
    public R<UserMemory> update(@PathVariable String id,
                                @Valid @RequestBody UserMemoryUpdateRequest request) {
        return R.ok(userMemoryService.update(currentTenantId(), currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除当前用户记忆")
    public R<Void> delete(@PathVariable String id) {
        userMemoryService.delete(currentTenantId(), currentUserId(), id);
        return R.ok();
    }

    private String currentTenantId() {
        return SecurityUtil.getCurrentTenantId();
    }

    private String currentUserId() {
        return SecurityUtil.getCurrentUserId();
    }
}
