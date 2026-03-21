package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.system.UserCreateRequest;
import com.schemaplexai.model.dto.system.UserQueryRequest;
import com.schemaplexai.model.dto.system.UserUpdateRequest;
import com.schemaplexai.model.vo.system.UserVO;
import com.schemaplexai.service.config.UserService;
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

/**
 * 用户管理控制器
 */
@RestController
@RequestMapping("/system/users")
@RequiredArgsConstructor
@Tag(name = "用户管理")
public class UserController {

    private final UserService userService;

    @GetMapping
    @Operation(summary = "分页查询用户列表")
    public R<PageResult<UserVO>> list(UserQueryRequest request) {
        return R.ok(userService.listUsers(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取用户详情")
    public R<UserVO> getById(@PathVariable String id) {
        return R.ok(userService.getUserById(id));
    }

    @PostMapping
    @Operation(summary = "创建用户")
    public R<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return R.ok(userService.createUser(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新用户")
    public R<UserVO> update(@PathVariable String id, @Valid @RequestBody UserUpdateRequest request) {
        return R.ok(userService.updateUser(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除用户")
    public R<Void> delete(@PathVariable String id) {
        userService.deleteUser(id);
        return R.ok();
    }

    @PutMapping("/{id}/password/reset")
    @Operation(summary = "重置用户密码")
    public R<Void> resetPassword(@PathVariable String id, @RequestBody String newPassword) {
        userService.resetPassword(id, newPassword);
        return R.ok();
    }
}
