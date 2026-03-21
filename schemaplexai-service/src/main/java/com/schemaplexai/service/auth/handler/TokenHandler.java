package com.schemaplexai.service.auth.handler;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Token 处理器 — 封装 JWT Token 的生成、存储、验证和撤销
 */
@Component
@RequiredArgsConstructor
public class TokenHandler {

    private final StringRedisTemplate redisTemplate;

    @Value("${jwt.secret:default-secret-key-at-least-32-characters}")
    private String jwtSecret;

    @Value("${jwt.access-token-expire:86400}")
    private long accessTokenExpire;

    @Value("${jwt.refresh-token-expire:604800}")
    private long refreshTokenExpire;

    private static final String REFRESH_TOKEN_PREFIX = "sf:auth:token:refresh:";

    /**
     * Token 对（不可变数据载体）
     */
    public record TokenPair(String accessToken, String refreshToken) {
    }

    /**
     * 生成 AccessToken + RefreshToken 并存储到 Redis
     */
    public TokenPair generateAndStoreTokenPair(String userId, String tenantId, List<String> roleCodes) {
        var accessToken = JwtUtil.generateAccessToken(
                userId, tenantId, roleCodes, jwtSecret, accessTokenExpire * 1000
        );
        var refreshToken = JwtUtil.generateRefreshToken(
                userId, jwtSecret, refreshTokenExpire * 1000
        );

        storeRefreshToken(userId, refreshToken);
        return new TokenPair(accessToken, refreshToken);
    }

    /**
     * 仅生成新的 AccessToken（用于 refresh 场景）
     */
    public String generateAccessToken(String userId, String tenantId, List<String> roleCodes) {
        return JwtUtil.generateAccessToken(
                userId, tenantId, roleCodes, jwtSecret, accessTokenExpire * 1000
        );
    }

    /**
     * 验证 RefreshToken：签名校验 + Redis 匹配
     *
     * @param refreshToken 前端传入的 RefreshToken
     * @return 通过验证后的 userId
     * @throws BusinessException RefreshToken 无效时抛出
     */
    public String validateRefreshToken(String refreshToken) {
        DecodedJWT jwt = JwtUtil.verifyToken(refreshToken, jwtSecret);
        if (jwt == null || !JwtUtil.isRefreshToken(jwt)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        var userId = JwtUtil.getUserId(jwt);
        var storedToken = redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + userId);
        if (storedToken == null || !storedToken.equals(refreshToken)) {
            throw new BusinessException(ResultCode.REFRESH_TOKEN_INVALID);
        }

        return userId;
    }

    /**
     * 撤销 RefreshToken（登出时调用）
     */
    public void revokeRefreshToken(String userId) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + userId);
    }

    private void storeRefreshToken(String userId, String refreshToken) {
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + userId,
                refreshToken,
                refreshTokenExpire, TimeUnit.SECONDS
        );
    }
}
