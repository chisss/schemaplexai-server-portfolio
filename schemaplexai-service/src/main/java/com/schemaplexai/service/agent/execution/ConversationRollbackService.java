package com.schemaplexai.service.agent.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

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
    private final AgentExecutionMapper agentExecutionMapper;
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
        String tenantId = SecurityUtil.getCurrentTenantId();
        String userId = SecurityUtil.getCurrentUserId();
        if (tenantId == null || userId == null) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        String canonicalId = conversationId.startsWith(tenantId + ":") || conversationId.startsWith("team:")
                ? conversationId : tenantId + ":" + conversationId;
        List<AgentExecution> executions = findExecutions(tenantId, canonicalId);
        if (executions.isEmpty() && !conversationId.equals(canonicalId)) {
            canonicalId = conversationId;
            executions = findExecutions(tenantId, canonicalId);
        }
        if (executions.isEmpty()) {
            return 0;
        }
        if (executions.stream().anyMatch(execution -> !Objects.equals(userId, execution.getCreatedBy()))) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        chatMessageMapper.lockConversation(canonicalId);
        int deleted = chatMessageMapper.update(null,
                new LambdaUpdateWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getTenantId, tenantId)
                        .eq(ChatMessageEntity::getConversationId, canonicalId)
                        .gt(ChatMessageEntity::getTurnIndex, targetTurnIndex)
                        .set(ChatMessageEntity::getDeleted, 1));

        chatMemoryStore.invalidateCache(canonicalId);
        log.info("对话回滚完成: conversationId={}, targetTurn={}, deletedMessages={}",
                canonicalId, targetTurnIndex, deleted);
        return deleted;
    }

    private List<AgentExecution> findExecutions(String tenantId, String conversationId) {
        return agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .select(AgentExecution::getCreatedBy)
                .eq(AgentExecution::getTenantId, tenantId)
                .eq(AgentExecution::getConversationId, conversationId));
    }

    /**
     * 获取对话的最大轮次索引
     */
    public int getMaxTurnIndex(String conversationId) {
        ChatMessageEntity last = chatMessageMapper.selectOne(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .isNotNull(ChatMessageEntity::getTurnIndex)
                        .orderByDesc(ChatMessageEntity::getTurnIndex)
                        .last("LIMIT 1"));
        return last != null && last.getTurnIndex() != null ? last.getTurnIndex() : 0;
    }
}
