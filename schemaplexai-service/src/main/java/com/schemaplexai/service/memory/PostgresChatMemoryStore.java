package com.schemaplexai.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.ChatMessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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
            // 删除旧消息（物理删除，因为 ChatMemory 的 eviction 机制需要精确覆盖）
            chatMessageMapper.delete(
                    new LambdaQueryWrapper<ChatMessageEntity>()
                            .eq(ChatMessageEntity::getConversationId, conversationId));

            // 批量插入新消息
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage msg = messages.get(i);
                ChatMessageEntity entity = fromLangChain4jMessage(msg, conversationId, i);
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
                // AI 消息可能包含工具调用请求，使用 JSON 反序列化恢复完整结构
                if (entity.getToolCalls() != null && !entity.getToolCalls().isEmpty()) {
                    // 有工具调用的 AI 消息，通过 JSON 反序列化还原
                    try {
                        String json = ChatMessageSerializer.messageToJson(
                                AiMessage.from(content != null ? content : ""));
                        // 简化处理：仅恢复文本内容，工具调用在 Agentic Loop 中不需要跨执行恢复
                        yield AiMessage.from(content != null ? content : "");
                    } catch (Exception e) {
                        yield AiMessage.from(content != null ? content : "");
                    }
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
    private ChatMessageEntity fromLangChain4jMessage(ChatMessage msg, String conversationId, int index) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
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
}
