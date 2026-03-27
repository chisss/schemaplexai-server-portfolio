package com.schemaplexai.service.auth.handler;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
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
    private static final String WS_TICKET_PREFIX = "sf:auth:ws:ticket:";

    @Value("${jwt.ws-ticket-expire:60}")
    private long wsTicketExpire;

    /**
     * Token 对（不可变数据载体）
     */
    public record TokenPair(String accessToken, String refreshToken) {
    }

    /**
     * WS Ticket 负载
     */
    public record WsTicketPayload(String userId, String tenantId) {}

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

    /**
     * 生成一次性 WebSocket Ticket（短效）
     */
    public String generateWsTicket(String userId, String tenantId) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        String value = userId + "|" + tenantId;
        redisTemplate.opsForValue().set(
                WS_TICKET_PREFIX + ticket,
                value,
                wsTicketExpire,
                TimeUnit.SECONDS
        );
        return ticket;
    }

    /**
     * 消费一次性 WebSocket Ticket
     */
    public WsTicketPayload consumeWsTicket(String ticket) {
        if (!StringUtils.hasText(ticket)) {
            return null;
        }
        String cacheKey = WS_TICKET_PREFIX + ticket;
        String value = redisTemplate.opsForValue().getAndDelete(cacheKey);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String[] arr = value.split("\\|", 2);
        if (arr.length != 2 || !StringUtils.hasText(arr[0]) || !StringUtils.hasText(arr[1])) {
            return null;
        }
        return new WsTicketPayload(arr[0], arr[1]);
    }

    private void storeRefreshToken(String userId, String refreshToken) {
        redisTemplate.opsForValue().set(
                REFRESH_TOKEN_PREFIX + userId,
                refreshToken,
                refreshTokenExpire, TimeUnit.SECONDS
        );
    }
}
