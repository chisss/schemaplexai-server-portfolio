package com.schemaplexai.model.vo.auth;

/**
 * Token刷新响应VO（Java 21 record，不可变数据载体）
 *
 * @param accessToken  新的访问令牌
 * @param refreshToken 刷新令牌（可原值返回或轮换）
 */
public record TokenVO(String accessToken, String refreshToken) {
}
