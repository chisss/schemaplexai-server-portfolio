package com.schemaplexai.service.marketplace.ratelimit;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DefaultPluginRateLimiter implements PluginRateLimiter {

    private final int capacity;
    private final int refillPerSecond;
    private final int maxBuckets;
    private final long bucketIdleNanos;
    private final Map<String, Bucket> bucketMap = new ConcurrentHashMap<>();

    public DefaultPluginRateLimiter(
            @Value("${marketplace.ratelimit.capacity:20}") int capacity,
            @Value("${marketplace.ratelimit.refill-per-second:5}") int refillPerSecond,
            @Value("${marketplace.ratelimit.max-buckets:10000}") int maxBuckets,
            @Value("${marketplace.ratelimit.bucket-idle-seconds:1800}") int bucketIdleSeconds) {
        this.capacity = Math.max(capacity, 1);
        this.refillPerSecond = Math.max(refillPerSecond, 1);
        this.maxBuckets = Math.max(maxBuckets, 1000);
        this.bucketIdleNanos = Math.max(bucketIdleSeconds, 60) * 1_000_000_000L;
    }

    @Override
    public void acquire(String tenantId, String actionKey) {
        String normalizedTenant = StringUtils.hasText(tenantId) ? tenantId.trim() : "anonymous";
        String normalizedAction = StringUtils.hasText(actionKey) ? actionKey.trim() : "default";
        String key = normalizedTenant + ":" + normalizedAction;

        long now = System.nanoTime();
        evictExpiredBuckets(now);
        if (!bucketMap.containsKey(key) && bucketMap.size() >= maxBuckets) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "系统繁忙，请稍后重试");
        }

        Bucket bucket = bucketMap.computeIfAbsent(
                key, ignored -> new Bucket(capacity, refillPerSecond, System.nanoTime()));

        synchronized (bucket) {
            bucket.refill(now);
            if (bucket.tokens < 1.0d) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "请求过于频繁，请稍后重试");
            }
            bucket.tokens -= 1.0d;
            bucket.lastAccessNanos = now;
        }
    }

    private void evictExpiredBuckets(long nowNanos) {
        if (bucketMap.size() < maxBuckets) {
            return;
        }
        bucketMap.entrySet().removeIf(entry ->
            nowNanos - entry.getValue().lastAccessNanos > bucketIdleNanos);
    }

    private static final class Bucket {
        private final int capacity;
        private final int refillPerSecond;
        private double tokens;
        private long lastRefillNanos;
        private long lastAccessNanos;

        private Bucket(int capacity, int refillPerSecond, long initTimeNanos) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
            this.tokens = capacity;
            this.lastRefillNanos = initTimeNanos;
            this.lastAccessNanos = initTimeNanos;
        }

        private void refill(long nowNanos) {
            if (nowNanos <= lastRefillNanos) {
                return;
            }
            long elapsedNanos = nowNanos - lastRefillNanos;
            double refillTokens = elapsedNanos / 1_000_000_000d * refillPerSecond;
            if (refillTokens <= 0d) {
                return;
            }
            tokens = Math.min(capacity, tokens + refillTokens);
            lastRefillNanos = nowNanos;
        }
    }
}
