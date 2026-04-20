package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 对话回滚服务
 * <p>参考 Codex CLI 的 thread_rollback 机制：
 * 允许用户回滚对话到之前的某个轮次，
 * 软删除目标轮次之后的所有消息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationRollbackService {

    private final ChatMessageMapper chatMessageMapper;
    private final CompositeChatMemoryStore chatMemoryStore;

    /**
     * 回滚对话到指定轮次
     *
     * @param conversationId 对话ID
     * @param targetTurnIndex 目标轮次索引（保留该轮次及之前的消息）
     * @return 被删除的消息数
     */
    @Transactional
    public int rollback(String conversationId, int targetTurnIndex) {
        List<ChatMessageEntity> toDelete = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .gt(ChatMessageEntity::getTurnIndex, targetTurnIndex)
                        .orderByAsc(ChatMessageEntity::getMessageIndex));

        if (toDelete.isEmpty()) {
            log.debug("无需回滚: conversationId={}, targetTurn={}", conversationId, targetTurnIndex);
            return 0;
        }

        int deleted = chatMessageMapper.update(null,
                new LambdaUpdateWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .gt(ChatMessageEntity::getTurnIndex, targetTurnIndex)
                        .set(ChatMessageEntity::getDeleted, 1));

        chatMemoryStore.invalidateCache(conversationId);

        log.info("对话回滚完成: conversationId={}, targetTurn={}, deletedMessages={}",
                conversationId, targetTurnIndex, deleted);
        return deleted;
    }

    /**
     * 获取对话的最大轮次索引
     */
    public int getMaxTurnIndex(String conversationId) {
        ChatMessageEntity last = chatMessageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .orderByDesc(ChatMessageEntity::getTurnIndex)
                        .last("LIMIT 1"));
        return last != null && last.getTurnIndex() != null ? last.getTurnIndex() : 0;
    }
}
