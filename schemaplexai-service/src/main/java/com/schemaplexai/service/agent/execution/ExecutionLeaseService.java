package com.schemaplexai.service.agent.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行租约服务
 * 用于防止多消费者重复消费同一execution。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionLeaseService {

    private final StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<String, LocalLease> localLeaseMap = new ConcurrentHashMap<>();

    @Value("${schemaplexai.execution.lease.allow-local-fallback:false}")
    private boolean allowLocalFallback;

    private static final DefaultRedisScript<Long> RENEW_IF_OWNER_SCRIPT = new DefaultRedisScript<>();
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER_SCRIPT = new DefaultRedisScript<>();

    static {
        RENEW_IF_OWNER_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('expire', KEYS[1], tonumber(ARGV[2])) " +
                        "else return 0 end");
        RENEW_IF_OWNER_SCRIPT.setResultType(Long.class);

        RELEASE_IF_OWNER_SCRIPT.setScriptText(
                "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                        "return redis.call('del', KEYS[1]) " +
                        "else return 0 end");
        RELEASE_IF_OWNER_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取执行租约
     *
     * @param executionId  执行ID
     * @param nodeId      节点ID
     * @param leaseSeconds 租约有效期（秒）
     * @return 获取成功返回true
     */
    public boolean acquireLease(String executionId, String nodeId, int leaseSeconds) {
        if (!StringUtils.hasText(executionId) || !StringUtils.hasText(nodeId)) {
            return false;
        }
        String key = leaseKey(executionId);
        try {
            Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(key, nodeId, Duration.ofSeconds(leaseSeconds));
            return Boolean.TRUE.equals(ok);
        } catch (RedisConnectionFailureException e) {
            if (!allowLocalFallback) {
                log.error("Redis不可用，拒绝领取租约(禁止本地降级): executionId={}", executionId, e);
                return false;
            }
            return acquireLocalLease(executionId, nodeId, leaseSeconds);
        }
    }

    /**
     * 续约执行租约
     *
     * @param executionId  执行ID
     * @param nodeId      节点ID（需与持有者匹配）
     * @param leaseSeconds 续期时长（秒）
     * @return 续约成功返回true
     */
    public boolean renewLease(String executionId, String nodeId, int leaseSeconds) {
        if (!StringUtils.hasText(executionId) || !StringUtils.hasText(nodeId)) {
            return false;
        }
        String key = leaseKey(executionId);
        try {
            Long renewed = stringRedisTemplate.execute(
                    RENEW_IF_OWNER_SCRIPT,
                    Collections.singletonList(key),
                    nodeId,
                    String.valueOf(leaseSeconds));
            return Long.valueOf(1L).equals(renewed);
        } catch (RedisConnectionFailureException e) {
            return allowLocalFallback && renewLocalLease(executionId, nodeId, leaseSeconds);
        }
    }

    /**
     * 释放执行租约
     *
     * @param executionId 执行ID
     * @param nodeId      节点ID（仅持有者可释放）
     */
    public void releaseLease(String executionId, String nodeId) {
        if (!StringUtils.hasText(executionId) || !StringUtils.hasText(nodeId)) {
            return;
        }
        String key = leaseKey(executionId);
        try {
            stringRedisTemplate.execute(RELEASE_IF_OWNER_SCRIPT, Collections.singletonList(key), nodeId);
        } catch (RedisConnectionFailureException e) {
            if (allowLocalFallback) {
                releaseLocalLease(executionId, nodeId);
            }
        }
    }

    /**
     * 检查租约是否有效
     */
    public boolean isLeaseValid(String executionId, String nodeId) {
        if (!StringUtils.hasText(executionId) || !StringUtils.hasText(nodeId)) {
            return false;
        }
        String key = leaseKey(executionId);
        try {
            String owner = stringRedisTemplate.opsForValue().get(key);
            return nodeId.equals(owner);
        } catch (RedisConnectionFailureException e) {
            return allowLocalFallback && isLocalLeaseValid(executionId, nodeId);
        }
    }

    private boolean acquireLocalLease(String executionId, String nodeId, int leaseSeconds) {
        long expireAtMs = System.currentTimeMillis() + leaseSeconds * 1000L;
        AtomicBoolean acquired = new AtomicBoolean(false);
        localLeaseMap.compute(executionId, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                acquired.set(true);
                return new LocalLease(nodeId, expireAtMs);
            }
            if (nodeId.equals(existing.owner())) {
                acquired.set(true);
                return new LocalLease(nodeId, expireAtMs);
            }
            return existing;
        });
        return acquired.get();
    }

    private boolean renewLocalLease(String executionId, String nodeId, int leaseSeconds) {
        long expireAtMs = System.currentTimeMillis() + leaseSeconds * 1000L;
        AtomicBoolean renewed = new AtomicBoolean(false);
        localLeaseMap.computeIfPresent(executionId, (k, existing) -> {
            if (existing.isExpired()) return null;
            if (nodeId.equals(existing.owner())) {
                renewed.set(true);
                return new LocalLease(nodeId, expireAtMs);
            }
            return existing;
        });
        return renewed.get();
    }

    private void releaseLocalLease(String executionId, String nodeId) {
        localLeaseMap.computeIfPresent(executionId, (k, existing) -> {
            if (existing.isExpired()) return null;
            return nodeId.equals(existing.owner()) ? null : existing;
        });
    }

    private boolean isLocalLeaseValid(String executionId, String nodeId) {
        LocalLease lease = localLeaseMap.get(executionId);
        if (lease == null || lease.isExpired()) {
            localLeaseMap.remove(executionId, lease);
            return false;
        }
        return nodeId.equals(lease.owner());
    }

    private String leaseKey(String executionId) {
        return "sf:exec:lease:" + executionId;
    }

    private record LocalLease(String owner, long expireAtMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() >= expireAtMs;
        }
    }
}
