package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.auth.LoginRequest;
import com.schemaplexai.model.dto.auth.RefreshTokenRequest;
import com.schemaplexai.model.vo.auth.LoginVO;
import com.schemaplexai.model.vo.auth.TokenVO;
import com.schemaplexai.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证授权控制器
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证授权")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    public R<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return R.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新Token")
    public R<TokenVO> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(authService.refreshToken(request));
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出")
    public R<Void> logout() {
        String userId = SecurityUtil.getCurrentUserId();
        if (userId != null) {
            authService.logout(userId);
        }
        return R.ok();
    }
}
