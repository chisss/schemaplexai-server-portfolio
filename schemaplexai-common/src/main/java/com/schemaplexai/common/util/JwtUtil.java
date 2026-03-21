package com.schemaplexai.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.util.Date;
import java.util.List;

/**
 * JWT工具类 - Token生成/验证/解析
 */
public final class JwtUtil {

    private JwtUtil() {}

    /**
     * 生成AccessToken
     *
     * @param userId   用户ID
     * @param tenantId 租户ID
     * @param roles    角色列表
     * @param secret   签名密钥
     * @param expireMs 过期时间（毫秒）
     */
    public static String generateAccessToken(String userId, String tenantId, List<String> roles,
                                              String secret, long expireMs) {
        return JWT.create()
                .withSubject(userId)
                .withClaim("tid", tenantId)
                .withClaim("roles", roles)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + expireMs))
                .sign(Algorithm.HMAC256(secret));
    }

    /**
     * 生成RefreshToken
     */
    public static String generateRefreshToken(String userId, String secret, long expireMs) {
        return JWT.create()
                .withSubject(userId)
                .withClaim("type", "refresh")
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + expireMs))
                .sign(Algorithm.HMAC256(secret));
    }

    /**
     * 验证并解析Token
     *
     * @return DecodedJWT，验证失败返回null
     */
    public static DecodedJWT verifyToken(String token, String secret) {
        try {
            return JWT.require(Algorithm.HMAC256(secret)).build().verify(token);
        } catch (JWTVerificationException e) {
            return null;
        }
    }

    /**
     * 从Token中获取用户ID
     */
    public static String getUserId(DecodedJWT jwt) {
        return jwt.getSubject();
    }

    /**
     * 从Token中获取租户ID
     */
    public static String getTenantId(DecodedJWT jwt) {
        return jwt.getClaim("tid").asString();
    }

    /**
     * 从Token中获取角色列表
     */
    public static List<String> getRoles(DecodedJWT jwt) {
        return jwt.getClaim("roles").asList(String.class);
    }

    /**
     * 判断是否为RefreshToken
     */
    public static boolean isRefreshToken(DecodedJWT jwt) {
        return "refresh".equals(jwt.getClaim("type").asString());
    }
}
