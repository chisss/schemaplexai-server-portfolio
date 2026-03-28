package com.schemaplexai.service.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 基于 Redis 的 ChatMemoryStore 缓存层实现
 *
 * <p>Key 格式: sf:memory:chat:{conversationId}
 * <p>存储格式: 将 List<ChatMessage> 序列化为 JSON 字符串
 * <p>TTL: 2小时（活跃对话自动续期）
 *
 * <p>所有操作均 try-catch，Redis 不可用时静默返回空/忽略，不影响主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "sf:memory:chat:";
    private static final Duration TTL = Duration.ofHours(2);

    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                return null; // 返回 null 表示缓存未命中，由上层决定是否回退
            }
            List<ChatMessage> messages = ChatMessageDeserializer.messagesFromJson(value.toString());
            log.debug("Redis 缓存命中对话历史: key={}, count={}", key, messages.size());
            return messages;
        } catch (Exception e) {
            log.debug("Redis 读取对话历史失败（降级）: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = buildKey(memoryId);
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            redisTemplate.opsForValue().set(key, json, TTL);
            log.debug("Redis 缓存更新对话历史: key={}, count={}", key, messages.size());
        } catch (Exception e) {
            log.debug("Redis 写入对话历史失败（忽略）: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = buildKey(memoryId);
        try {
            redisTemplate.delete(key);
            log.debug("Redis 删除对话历史: key={}", key);
        } catch (Exception e) {
            log.debug("Redis 删除对话历史失败（忽略）: key={}, error={}", key, e.getMessage());
        }
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId.toString();
    }
}
