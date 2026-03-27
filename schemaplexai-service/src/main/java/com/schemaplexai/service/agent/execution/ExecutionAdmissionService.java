package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 执行准入控制（三维限流：租户/Agent/模型）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionAdmissionService {

    private final StringRedisTemplate stringRedisTemplate;

    @Value("${schemaplexai.execution.admission.tenant-max:20}")
    private int tenantMaxConcurrency;

    @Value("${schemaplexai.execution.admission.agent-max:6}")
    private int agentMaxConcurrency;

    @Value("${schemaplexai.execution.admission.model-max:12}")
    private int modelMaxConcurrency;

    @Value("${schemaplexai.execution.admission.counter-ttl-seconds:3600}")
    private int counterTtlSeconds;

    private final ConcurrentHashMap<String, AtomicInteger> localCounter = new ConcurrentHashMap<>();

    /**
     * 申请执行准入令牌（三维检查）
     *
     * @param tenantId    租户ID
     * @param agentId     AgentID
     * @param modelKey    模型标识
     * @param executionId 执行ID
     * @return 准入令牌，失败抛异常
     */
    public AdmissionToken acquire(String tenantId, String agentId, String modelKey, String executionId) {
        String normalizedTenant = normalize(tenantId, "default");
        String normalizedAgent = normalize(agentId, "default");
        String normalizedModel = normalize(modelKey, "unknown");

        String tenantKey = "sf:exec:admission:tenant:" + normalizedTenant;
        String agentKey = "sf:exec:admission:agent:" + normalizedTenant + ":" + normalizedAgent;
        String modelDimensionKey = "sf:exec:admission:model:" + normalizedModel;

        try {
            acquireWithRedis(tenantKey, tenantMaxConcurrency);
            boolean agentOk = false;
            boolean modelOk = false;
            try {
                acquireWithRedis(agentKey, agentMaxConcurrency);
                agentOk = true;
                acquireWithRedis(modelDimensionKey, modelMaxConcurrency);
                modelOk = true;
            } finally {
                if (!agentOk) {
                    releaseWithRedis(tenantKey);
                } else if (!modelOk) {
                    releaseWithRedis(agentKey);
                    releaseWithRedis(tenantKey);
                }
            }
            return AdmissionToken.builder()
                    .executionId(executionId)
                    .tenantKey(tenantKey)
                    .agentKey(agentKey)
                    .modelKey(modelDimensionKey)
                    .backend(Backend.REDIS)
                    .build();
        } catch (BusinessException be) {
            throw be;
        } catch (RedisConnectionFailureException redisEx) {
            log.warn("Redis不可用，降级本地限流: executionId={}", executionId, redisEx);
            return acquireWithLocalFallback(tenantKey, agentKey, modelDimensionKey, executionId);
        }
    }

    /**
     * 释放执行准入令牌
     */
    public void release(AdmissionToken token) {
        if (token == null) {
            return;
        }
        if (token.getBackend() == Backend.REDIS) {
            releaseWithRedis(token.getModelKey());
            releaseWithRedis(token.getAgentKey());
            releaseWithRedis(token.getTenantKey());
        } else {
            releaseWithLocal(token.getModelKey());
            releaseWithLocal(token.getAgentKey());
            releaseWithLocal(token.getTenantKey());
        }
    }

    /**
     * 检查是否允许接受任务（不占用配额）
     */
    public boolean canAcceptTask(String tenantId, String agentId) {
        String normalizedTenant = normalize(tenantId, "default");
        String normalizedAgent = normalize(agentId, "default");

        String tenantKey = "sf:exec:admission:tenant:" + normalizedTenant;
        String agentKey = "sf:exec:admission:agent:" + normalizedTenant + ":" + normalizedAgent;

        try {
            return checkWithRedis(tenantKey, tenantMaxConcurrency)
                    && checkWithRedis(agentKey, agentMaxConcurrency);
        } catch (RedisConnectionFailureException e) {
            return checkWithLocal(tenantKey, tenantMaxConcurrency)
                    && checkWithLocal(agentKey, agentMaxConcurrency);
        }
    }

    /**
     * 检查模型维度是否允许接受任务
     */
    public boolean canAcceptModelTask(String modelKey) {
        String normalizedModel = normalize(modelKey, "unknown");
        String modelDimensionKey = "sf:exec:admission:model:" + normalizedModel;
        try {
            return checkWithRedis(modelDimensionKey, modelMaxConcurrency);
        } catch (RedisConnectionFailureException e) {
            return checkWithLocal(modelDimensionKey, modelMaxConcurrency);
        }
    }

    /**
     * 预留模型槽位（消费配额）
     */
    public void reserveModelSlot(String modelKey) {
        String normalizedModel = normalize(modelKey, "unknown");
        String modelDimensionKey = "sf:exec:admission:model:" + normalizedModel;
        try {
            acquireWithRedis(modelDimensionKey, modelMaxConcurrency);
        } catch (RedisConnectionFailureException e) {
            acquireWithLocal(modelDimensionKey, modelMaxConcurrency);
        }
    }

    private AdmissionToken acquireWithLocalFallback(String tenantKey, String agentKey, String modelKey, String executionId) {
        acquireWithLocal(tenantKey, tenantMaxConcurrency);
        boolean agentOk = false;
        boolean modelOk = false;
        try {
            acquireWithLocal(agentKey, agentMaxConcurrency);
            agentOk = true;
            acquireWithLocal(modelKey, modelMaxConcurrency);
            modelOk = true;
        } finally {
            if (!agentOk) {
                releaseWithLocal(tenantKey);
            } else if (!modelOk) {
                releaseWithLocal(agentKey);
                releaseWithLocal(tenantKey);
            }
        }
        return AdmissionToken.builder()
                .executionId(executionId)
                .tenantKey(tenantKey)
                .agentKey(agentKey)
                .modelKey(modelKey)
                .backend(Backend.LOCAL)
                .build();
    }

    private void acquireWithRedis(String key, int limit) {
        Long current = stringRedisTemplate.opsForValue().increment(key);
        if (current == null) {
            throw new RedisConnectionFailureException("Redis计数失败");
        }
        if (current == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(counterTtlSeconds));
        }
        if (current > limit) {
            stringRedisTemplate.opsForValue().decrement(key);
            throw new BusinessException(ResultCode.AGENT_BUSY, "并发已达上限");
        }
    }

    private boolean checkWithRedis(String key, int limit) {
        String val = stringRedisTemplate.opsForValue().get(key);
        if (val == null) {
            return true;
        }
        int current = Integer.parseInt(val);
        return current < limit;
    }

    private void releaseWithRedis(String key) {
        if (!StringUtils.hasText(key)) {
            return;
        }
        try {
            Long left = stringRedisTemplate.opsForValue().decrement(key);
            if (left != null && left <= 0) {
                stringRedisTemplate.delete(key);
            }
        } catch (Exception e) {
            log.debug("释放Redis限流计数失败: key={}", key, e);
        }
    }

    private void acquireWithLocal(String key, int limit) {
        AtomicInteger counter = localCounter.computeIfAbsent(key, ignored -> new AtomicInteger(0));
        int current = counter.incrementAndGet();
        if (current > limit) {
            counter.decrementAndGet();
            throw new BusinessException(ResultCode.AGENT_BUSY, "并发已达上限");
        }
    }

    private boolean checkWithLocal(String key, int limit) {
        AtomicInteger counter = localCounter.get(key);
        if (counter == null) {
            return true;
        }
        return counter.get() < limit;
    }

    private void releaseWithLocal(String key) {
        AtomicInteger counter = localCounter.get(key);
        if (counter == null) {
            return;
        }
        int left = counter.decrementAndGet();
        if (left <= 0) {
            localCounter.remove(key, counter);
        }
    }

    private String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public enum Backend {
        REDIS,
        LOCAL
    }

    @Getter
    @Builder
    public static class AdmissionToken {
        private String executionId;
        private String tenantKey;
        private String agentKey;
        private String modelKey;
        private Backend backend;
    }
}
