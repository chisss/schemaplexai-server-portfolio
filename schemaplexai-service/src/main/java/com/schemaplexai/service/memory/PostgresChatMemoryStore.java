package com.schemaplexai.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL 的 ChatMemoryStore 实现
 *
 * <p>使用 sf_chat_message 表持久化对话消息。
 * memoryId 对应 conversation_id 字段。
 * 使用 LangChain4J 的 ChatMessageSerializer/Deserializer 进行 JSON 序列化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresChatMemoryStore implements ChatMemoryStore {

    private final AgentExecutionMapper agentExecutionMapper;
    private final ChatMessageMapper chatMessageMapper;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        try {
            List<ChatMessageEntity> entities = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId)
                            .orderByAsc(ChatMessageEntity::getMessageIndex));

            List<ChatMessage> messages = entities.stream()
                    .map(this::toLangChain4jMessage)
                    .filter(java.util.Objects::nonNull)
                    .toList();

            log.debug("从 PostgreSQL 加载对话历史: conversationId={}, count={}", conversationId, messages.size());
            return messages;
        } catch (Exception e) {
            log.error("从 PostgreSQL 加载对话历史失败: conversationId={}", conversationId, e);
            return List.of();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String conversationId = memoryId.toString();
        try {
            AgentExecution messageOwner = resolveMessageOwner(conversationId);
            if (messageOwner == null) {
                log.warn("未找到对话所属执行记录，跳过写入对话历史: conversationId={}", conversationId);
                return;
            }

            // 删除旧消息（物理删除，因为 ChatMemory 的 eviction 机制需要精确覆盖）
            chatMessageMapper.delete(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId));

            // 批量插入新消息
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                ChatMessageEntity entity = fromLangChain4jMessage(msg, messageOwner, conversationId, i);
                if (entity != null) {
                    chatMessageMapper.insert(entity);
                }
            }

            log.debug("更新 PostgreSQL 对话历史: conversationId={}, count={}", conversationId, messages.size());
        } catch (Exception e) {
            log.error("更新 PostgreSQL 对话历史失败: conversationId={}", conversationId, e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessages(Object memoryId) {
        String conversationId = memoryId.toString();
        try {
            chatMessageMapper.delete(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId));
            log.debug("删除 PostgreSQL 对话历史: conversationId={}", conversationId);
        } catch (Exception e) {
            log.error("删除 PostgreSQL 对话历史失败: conversationId={}", conversationId, e);
        }
    }

    // =========================================================================
    //  消息转换：LangChain4J ChatMessage ↔ ChatMessageEntity
    // =========================================================================

    /**
     * ChatMessageEntity → LangChain4J ChatMessage
     */
    private ChatMessage toLangChain4jMessage(ChatMessageEntity entity) {
        String type = entity.getMessageType();
        String content = entity.getTextContent();

        return switch (type) {
            case "SYSTEM" -> SystemMessage.from(content != null ? content : "");
            case "USER" -> UserMessage.from(content != null ? content : "");
            case "AI" -> {
                List<ToolExecutionRequest> toolRequests = restoreToolExecutionRequests(entity.getToolCalls());
                if (!toolRequests.isEmpty()) {
                    // 恢复工具调用请求，避免空 AI 消息在下一轮请求中变成非法 messages。
                    if (StringUtils.hasText(content)) {
                        yield AiMessage.from(content, toolRequests);
                    }
                    yield AiMessage.from(toolRequests);
                }
                yield AiMessage.from(content != null ? content : "");
            }
            case "TOOL_EXECUTION_RESULT" -> ToolExecutionResultMessage.from(
                    entity.getToolCallId(),
                    entity.getToolName(),
                    content != null ? content : "");
            default -> {
                log.warn("未知的消息类型: {}", type);
                yield null;
            }
        };
    }

    /**
     * LangChain4J ChatMessage → ChatMessageEntity
     */
    private ChatMessageEntity fromLangChain4jMessage(ChatMessage msg, AgentExecution messageOwner,
                                                     String conversationId, int index) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setTenantId(messageOwner.getTenantId());
        entity.setConversationId(conversationId);
        entity.setExecutionId(messageOwner.getId());
        entity.setAgentId(messageOwner.getAgentId());
        entity.setMessageIndex(index);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setDeleted(0);

        if (msg instanceof SystemMessage sm) {
            entity.setMessageType("SYSTEM");
            entity.setTextContent(sm.text());
        } else if (msg instanceof UserMessage um) {
            entity.setMessageType("USER");
            entity.setTextContent(um.singleText());
        } else if (msg instanceof AiMessage am) {
            entity.setMessageType("AI");
            entity.setTextContent(am.text());
            // 工具调用请求序列化到 toolCalls 字段
            if (am.hasToolExecutionRequests() && am.toolExecutionRequests() != null) {
                var toolCallMaps = am.toolExecutionRequests().stream()
                        .map(req -> {
                            java.util.Map<String, Object> map = new java.util.HashMap<>();
                            map.put("id", req.id());
                            map.put("name", req.name());
                            map.put("arguments", req.arguments());
                            return map;
                        })
                        .toList();
                entity.setToolCalls(toolCallMaps);
            }
        } else if (msg instanceof ToolExecutionResultMessage trm) {
            entity.setMessageType("TOOL_EXECUTION_RESULT");
            entity.setTextContent(trm.text());
            entity.setToolCallId(trm.id());
            entity.setToolName(trm.toolName());
        } else {
            log.warn("不支持的消息类型: {}", msg.getClass().getSimpleName());
            return null;
        }

        return entity;
    }

    private List<ToolExecutionRequest> restoreToolExecutionRequests(List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }
        List<ToolExecutionRequest> requests = new ArrayList<>();
        for (Map<String, Object> toolCall : toolCalls) {
            if (toolCall == null || toolCall.isEmpty()) {
                continue;
            }
            String name = toolCall.get("name") != null ? String.valueOf(toolCall.get("name")) : null;
            if (!StringUtils.hasText(name)) {
                continue;
            }
            requests.add(ToolExecutionRequest.builder()
                    .id(toolCall.get("id") != null ? String.valueOf(toolCall.get("id")) : null)
                    .name(name)
                    .arguments(toolCall.get("arguments") != null ? String.valueOf(toolCall.get("arguments")) : null)
                    .build());
        }
        return requests;
    }

    private AgentExecution resolveMessageOwner(String conversationId) {
        return agentExecutionMapper.selectOne(
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getConversationId, conversationId)
                        .orderByDesc(AgentExecution::getCreatedAt)
                        .last("LIMIT 1"));
    }
}
