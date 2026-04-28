package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.user.UserMemorySettingUpdateRequest;
import com.schemaplexai.model.entity.UserMemorySetting;
import com.schemaplexai.service.user.UserMemoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户记忆设置控制器
 */
@RestController
@RequestMapping("/user-memory-settings")
@RequiredArgsConstructor
@Tag(name = "用户记忆设置")
public class UserMemorySettingController {

    private final UserMemoryService userMemoryService;

    @GetMapping("/me")
    @Operation(summary = "查询当前用户记忆设置")
    public R<UserMemorySetting> getMine() {
        return R.ok(userMemoryService.getOrCreateSetting(
                SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId()));
    }

    @PutMapping("/me")
    @Operation(summary = "更新当前用户记忆设置")
    public R<UserMemorySetting> updateMine(@RequestBody UserMemorySettingUpdateRequest request) {
        return R.ok(userMemoryService.updateSetting(
                SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(), request));
    }
}
