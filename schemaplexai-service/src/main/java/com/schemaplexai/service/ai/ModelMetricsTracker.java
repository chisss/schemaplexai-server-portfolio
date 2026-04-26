package com.schemaplexai.service.ai;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型实时指标跟踪器
 */
@Component
@RequiredArgsConstructor
public class ModelMetricsTracker {

    private static final Duration METRIC_TTL = Duration.ofMinutes(2);
    private static final int MAX_LATENCY_SAMPLES = 200;

    private final RedisTemplate<String, Object> redisTemplate;

    public void recordSuccess(String modelId, long latencyMs, long inputTokens, long outputTokens, BigDecimal cost) {
        record(modelId, latencyMs, false);
    }

    public void recordFailure(String modelId, long latencyMs) {
        record(modelId, latencyMs, true);
    }

    public RuntimeMetrics loadMetrics(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            return RuntimeMetrics.empty();
        }
        Long requestCount = readLong(counterKey(modelId, "requests"));
        Long errorCount = readLong(counterKey(modelId, "errors"));
        List<Object> latencies = redisTemplate.opsForList().range(latencyKey(modelId), 0, -1);
        List<Long> samples = new ArrayList<>();
        if (!CollectionUtils.isEmpty(latencies)) {
            for (Object value : latencies) {
                if (value != null) {
                    samples.add(Long.parseLong(String.valueOf(value)));
                }
            }
        }
        samples.sort(Long::compareTo);
        long p95Latency = samples.isEmpty() ? 0L : samples.get(Math.min(samples.size() - 1, Math.max(0, (int) Math.ceil(samples.size() * 0.95) - 1)));
        double errorRate = requestCount == null || requestCount == 0 ? 0D : (errorCount == null ? 0D : errorCount * 100.0 / requestCount);
        Long timestamp = readLong(lastUpdatedKey(modelId));
        LocalDateTime lastUpdated = timestamp == null || timestamp <= 0
                ? null
                : LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp), ZoneId.systemDefault());
        return new RuntimeMetrics(
                requestCount == null ? 0L : requestCount,
                errorCount == null ? 0L : errorCount,
                errorRate,
                p95Latency,
                resolveHealthStatus(errorRate),
                lastUpdated
        );
    }

    public Map<String, RuntimeMetrics> loadMetrics(List<String> modelIds) {
        Map<String, RuntimeMetrics> metricsMap = new LinkedHashMap<>();
        if (CollectionUtils.isEmpty(modelIds)) {
            return metricsMap;
        }
        for (String modelId : modelIds) {
            metricsMap.put(modelId, loadMetrics(modelId));
        }
        return metricsMap;
    }

    private void record(String modelId, long latencyMs, boolean failed) {
        if (!StringUtils.hasText(modelId)) {
            return;
        }
        increment(counterKey(modelId, "requests"));
        if (failed) {
            increment(counterKey(modelId, "errors"));
        }
        redisTemplate.opsForList().rightPush(latencyKey(modelId), latencyMs);
        redisTemplate.opsForList().trim(latencyKey(modelId), -MAX_LATENCY_SAMPLES, -1);
        redisTemplate.expire(latencyKey(modelId), METRIC_TTL);
        redisTemplate.opsForValue().set(lastUpdatedKey(modelId), System.currentTimeMillis(), METRIC_TTL);
    }

    private void increment(String key) {
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, METRIC_TTL);
    }

    private Long readLong(String key) {
        Object value = redisTemplate.opsForValue().get(key);
        return value == null ? null : Long.parseLong(String.valueOf(value));
    }

    private String counterKey(String modelId, String metric) {
        return "model:" + modelId + ":metrics:" + metric;
    }

    private String latencyKey(String modelId) {
        return "model:" + modelId + ":metrics:latencies";
    }

    private String lastUpdatedKey(String modelId) {
        return "model:" + modelId + ":metrics:last_updated";
    }

    private String resolveHealthStatus(double errorRate) {
        if (errorRate >= 20D) {
            return "critical";
        }
        if (errorRate >= 5D) {
            return "warning";
        }
        return "healthy";
    }

    public record RuntimeMetrics(
            long requestCount1m,
            long errorCount1m,
            double errorRate1m,
            long p95LatencyMs,
            String healthStatus,
            LocalDateTime lastUpdated
    ) {
        public static RuntimeMetrics empty() {
            return new RuntimeMetrics(0L, 0L, 0D, 0L, "healthy", null);
        }
    }
}
