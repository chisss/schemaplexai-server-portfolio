package com.schemaplexai.service.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    public List<ChatMessage> loadWindow(String conversationId, String executionId, int limit) {
        return postgresChatMemoryStore.loadWindow(conversationId, executionId, limit);
    }

    public void appendMessages(String conversationId, String executionId,
                               List<PostgresChatMemoryStore.ArchivedMessage> messages) {
        postgresChatMemoryStore.appendMessages(conversationId, executionId, messages);
    }

    public void appendMissingToolResults(String conversationId, String executionId,
                                         List<ToolExecutionResultMessage> results) {
        postgresChatMemoryStore.appendMissingToolResults(conversationId, executionId, results);
    }

    public List<ChatMessage> loadExecutionForExtraction(String conversationId, String executionId) {
        return postgresChatMemoryStore.loadExecutionForExtraction(conversationId, executionId);
    }

    public List<ChatMessage> loadAuthoredExecutionInputs(String conversationId, String executionId) {
        return postgresChatMemoryStore.loadAuthoredExecutionInputs(conversationId, executionId);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 事务内只读数据库，避免读取旧缓存或向缓存发布未提交的消息。
            return postgresChatMemoryStore.getMessages(memoryId);
        }
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
        List<ChatMessage> snapshot = List.copyOf(messages);
        postgresChatMemoryStore.updateMessages(memoryId, snapshot);
        afterCommit(() -> redisChatMemoryStore.updateMessages(memoryId, snapshot));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        postgresChatMemoryStore.deleteMessages(memoryId);
        invalidateCache(memoryId);
    }

    /**
     * 失效指定会话的 Redis 缓存（回滚后调用，强制从 PG 重新加载）
     */
    public void invalidateCache(Object memoryId) {
        afterCommit(() -> {
            redisChatMemoryStore.deleteMessages(memoryId);
            log.debug("已失效会话缓存: memoryId={}", memoryId);
        });
    }

    private void afterCommit(Runnable updateCache) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            updateCache.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                updateCache.run();
            }
        });
    }
}
