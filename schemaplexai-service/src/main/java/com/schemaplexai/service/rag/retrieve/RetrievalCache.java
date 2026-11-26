package com.schemaplexai.service.rag.retrieve;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 检索结果缓存。
 *
 * <p>缓存键必须同时包含 <b>租户 + 项目范围 + query + 检索版本</b>：
 * 少任何一项都会出事 —— 少租户会串数据，少范围会拿别的项目的答案，
 * 少检索版本会让"改完切分配置仍命中旧结果"。
 *
 * <p>刻意不用 Caffeine：TTL 是按租户配置动态变化的，而 Caffeine 的 expireAfterWrite
 * 在构建期就要固定。自己实现一个带容量上限的 LRU + 惰性过期，代码量更小也更可控。
 */
@Slf4j
@Component
public class RetrievalCache {

    /** 单实例缓存条目上限，防止大租户把堆吃满 */
    static final int MAX_ENTRIES = 4096;

    private final ConcurrentMap<String, Entry> cache = new ConcurrentHashMap<>();

    public static String buildKey(String tenantId, Iterable<String> projectIds, String query,
                                  String retrievalVersion, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append(tenantId == null ? "" : tenantId).append('|');
        if (projectIds != null) {
            java.util.TreeSet<String> sorted = new java.util.TreeSet<>();
            projectIds.forEach(id -> {
                if (id != null) {
                    sorted.add(id);
                }
            });
            sb.append(String.join(",", sorted));
        }
        sb.append('|').append(query == null ? "" : query.trim())
                .append('|').append(retrievalVersion == null ? "" : retrievalVersion)
                .append('|').append(topK);
        return sb.toString();
    }

    public RetrievalOutcome get(String key) {
        if (key == null) {
            return null;
        }
        Entry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt < System.currentTimeMillis()) {
            cache.remove(key);
            return null;
        }
        return entry.outcome;
    }

    public void put(String key, RetrievalOutcome outcome, int ttlSeconds) {
        if (key == null || outcome == null || ttlSeconds <= 0) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            evictExpired();
            if (cache.size() >= MAX_ENTRIES) {
                // 仍满：清掉最早过期的 10%，实现简单且不会抖动
                cache.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue(
                                java.util.Comparator.comparingLong(e -> e.expiresAt)))
                        .limit(Math.max(1, MAX_ENTRIES / 10))
                        .map(Map.Entry::getKey)
                        .toList()
                        .forEach(cache::remove);
            }
        }
        cache.put(key, new Entry(outcome, System.currentTimeMillis() + ttlSeconds * 1000L));
    }

    /** 文件变更后按项目失效，避免"文档已更新但还答旧内容" */
    public int invalidateProject(String tenantId, String projectId) {
        if (tenantId == null) {
            return 0;
        }
        String prefix = tenantId + "|";
        java.util.List<String> victims = new java.util.ArrayList<>();
        for (String key : cache.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            if (projectId == null || key.contains(projectId)) {
                victims.add(key);
            }
        }
        victims.forEach(cache::remove);
        if (!victims.isEmpty()) {
            log.debug("检索缓存失效: tenantId={}, projectId={}, count={}", tenantId, projectId, victims.size());
        }
        return victims.size();
    }

    public int size() {
        evictExpired();
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt < now);
    }

    /** 缓存条目：只存最终结果，不存中间态（中间态复用价值低，占内存） */
    private record Entry(RetrievalOutcome outcome, long expiresAt) {
    }

    /** 便于测试观察：按 key 取剩余条目数（诊断用） */
    Map<String, Long> snapshotTtl() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        cache.forEach((key, entry) -> snapshot.put(key, entry.expiresAt - System.currentTimeMillis()));
        return snapshot;
    }
}
