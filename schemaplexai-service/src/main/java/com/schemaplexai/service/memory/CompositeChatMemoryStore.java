package com.schemaplexai.service.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 组合式 ChatMemoryStore（Redis L1 缓存 + PostgreSQL L2 持久化）
 *
 * <p>读取策略: Redis → hit → return; miss → PostgreSQL → cache to Redis → return
 * <p>写入策略: 先写 PostgreSQL（持久化），再写 Redis（缓存）
 * <p>删除策略: 同时删除 PostgreSQL + Redis
 *
 * <p>该组件作为 Agent 执行引擎构建 ChatMemory 时使用的默认 ChatMemoryStore。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeChatMemoryStore implements ChatMemoryStore {

    private final PostgresChatMemoryStore postgresChatMemoryStore;
    private final RedisChatMemoryStore redisChatMemoryStore;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // L1: 尝试从 Redis 读取
        List<ChatMessage> cached = redisChatMemoryStore.getMessages(memoryId);
        if (cached != null) {
            return cached;
        }

        // L2: 回退到 PostgreSQL
        List<ChatMessage> fromDb = postgresChatMemoryStore.getMessages(memoryId);

        // 回填 Redis 缓存（仅当有数据时）
        if (fromDb != null && !fromDb.isEmpty()) {
            redisChatMemoryStore.updateMessages(memoryId, fromDb);
        }

        return fromDb != null ? fromDb : List.of();
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // 先持久化到 PostgreSQL
        postgresChatMemoryStore.updateMessages(memoryId, messages);

        // 再更新 Redis 缓存
        redisChatMemoryStore.updateMessages(memoryId, messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        postgresChatMemoryStore.deleteMessages(memoryId);
        redisChatMemoryStore.deleteMessages(memoryId);
    }

    /**
     * 失效指定会话的 Redis 缓存（回滚后调用，强制从 PG 重新加载）
     */
    public void invalidateCache(Object memoryId) {
        redisChatMemoryStore.deleteMessages(memoryId);
        log.debug("已失效会话缓存: memoryId={}", memoryId);
    }
}
