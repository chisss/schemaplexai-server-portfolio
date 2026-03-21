package com.schemaplexai.model.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Token刷新请求DTO
 */
@Data
public class RefreshTokenRequest {

    /** 刷新令牌 */
    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
