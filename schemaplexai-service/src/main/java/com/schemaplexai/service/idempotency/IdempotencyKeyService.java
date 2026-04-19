package com.schemaplexai.service.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 基于 Redis 的幂等键服务。
 *
 * <p>约定：调用方自行构造幂等键（例如 {@code sf:idem:kb:upload:{tenantId}:{requestKey}}），
 * 本服务负责用 {@code SETNX + EXPIRE} 原子占位，防止重复请求产生副作用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyKeyService {

    /** 默认 TTL：5 分钟，覆盖典型上传流程（含前置扫描 + MinIO 落盘） */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    public static final String UPLOAD_PREFIX = "sf:idem:kb:upload:";

    private final StringRedisTemplate redisTemplate;

    /**
     * 尝试占位：首次请求返回 {@code true}，重复请求返回 {@code false}。
     *
     * @param key Redis 键
     * @param ttl 过期时间；null 时使用默认值
     */
    public boolean tryAcquire(String key, Duration ttl) {
        Duration effective = ttl == null ? DEFAULT_TTL : ttl;
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, "1", effective);
        boolean acquired = Boolean.TRUE.equals(ok);
        log.debug("幂等键占位: key={}, acquired={}", key, acquired);
        return acquired;
    }

    public boolean tryAcquire(String key) {
        return tryAcquire(key, DEFAULT_TTL);
    }

    /**
     * 主动释放键（通常用于上传失败后允许前端重试）。
     */
    public void release(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 构造上传场景幂等键：{@code sf:idem:kb:upload:{tenantId}:{clientKey}}。
     */
    public static String buildUploadKey(String tenantId, String clientKey) {
        return UPLOAD_PREFIX + tenantId + ":" + clientKey;
    }
}
