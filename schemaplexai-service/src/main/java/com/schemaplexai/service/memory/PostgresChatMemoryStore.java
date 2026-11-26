package com.schemaplexai.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.service.ai.ChatMessageText;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
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
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        List<ChatMessage> messages = chatMessageMapper.selectList(
                        new LambdaQueryWrapper<ChatMessageEntity>()
                                .eq(ChatMessageEntity::getConversationId, conversationId)
                                .orderByAsc(ChatMessageEntity::getMessageIndex))
                .stream()
                .map(this::toLangChain4jMessage)
                .toList();
        log.debug("从 PostgreSQL 加载对话历史: conversationId={}, count={}", conversationId, messages.size());
        return messages;
    }

    /** 单次读取最近的逻辑轮次，恢复时用原执行轮次约束视图。 */
    public List<ChatMessage> loadWindow(String conversationId, String executionId, int limit) {
        Integer turnIndex = findTurnIndex(conversationId, executionId);
        List<ChatMessageEntity> recent = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .and(turnIndex != null, scope -> scope.le(ChatMessageEntity::getTurnIndex, turnIndex)
                        .or().isNull(ChatMessageEntity::getTurnIndex))
                .apply("""
                        (execution_id = {0} OR NOT EXISTS (
                            SELECT 1 FROM sf_agent_execution source
                            WHERE source.id::text = sf_chat_message.execution_id
                            AND source.tenant_id = sf_chat_message.tenant_id
                            AND (source.temporary_chat IS TRUE OR source.memory_project_id IS DISTINCT FROM (
                                SELECT current_run.memory_project_id FROM sf_agent_execution current_run
                                WHERE current_run.id::text = {0}
                            ))
                        ))
                        """, executionId)
                .last("ORDER BY turn_index DESC NULLS LAST, message_index DESC LIMIT " + limit));
        return recent.reversed().stream().map(this::toLangChain4jMessage).toList();
    }

    public List<ChatMessage> loadExecutionWindow(String conversationId, String executionId, int limit) {
        List<ChatMessageEntity> recent = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .eq(ChatMessageEntity::getExecutionId, executionId)
                .orderByDesc(ChatMessageEntity::getMessageIndex)
                .last("LIMIT " + limit));
        return recent.reversed().stream().map(this::toLangChain4jMessage).toList();
    }

    /** 原始轨迹中的消息只在创建时写入，窗口淘汰不触碰这些记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void appendMessages(String conversationId, String executionId, List<ArchivedMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }
        AgentExecution owner = agentExecutionMapper.selectById(executionId);
        if (owner == null || !conversationId.equals(owner.getConversationId())) {
            throw new IllegalStateException("原始消息与执行记录不匹配");
        }
        chatMessageMapper.lockConversation(conversationId);
        Integer turnIndex = findTurnIndex(conversationId, executionId);
        int turn = turnIndex != null ? turnIndex : chatMessageMapper.nextTurnIndex(conversationId);
        int index = chatMessageMapper.nextMessageIndex(conversationId);
        for (ArchivedMessage message : messages) {
            ChatMessageEntity entity = fromLangChain4jMessage(message.message(), owner, conversationId, index++);
            entity.setArchiveEntry(true);
            entity.setAuthoredText(message.authoredText());
            entity.setTurnIndex(turn);
            if (chatMessageMapper.insert(entity) != 1) {
                throw new IllegalStateException("写入原始对话消息失败");
            }
        }
    }

    /** 恢复审批后补齐已提交的结果，跳过暂停前已写入的调用结果。 */
    @Transactional(rollbackFor = Exception.class)
    public void appendMissingToolResults(String conversationId, String executionId,
                                         List<ToolExecutionResultMessage> results) {
        if (results.isEmpty()) {
            return;
        }
        chatMessageMapper.lockConversation(conversationId);
        List<String> callIds = results.stream().map(ToolExecutionResultMessage::id).toList();
        Set<String> storedIds = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                        .select(ChatMessageEntity::getToolCallId)
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .eq(ChatMessageEntity::getExecutionId, executionId)
                        .eq(ChatMessageEntity::getMessageType, "TOOL_EXECUTION_RESULT")
                        .in(ChatMessageEntity::getToolCallId, callIds))
                .stream().map(ChatMessageEntity::getToolCallId).collect(Collectors.toSet());
        appendMessages(conversationId, executionId, results.stream()
                .filter(result -> storedIds.add(result.id()))
                .map(result -> new ArchivedMessage(result, null))
                .toList());
    }

    private Integer findTurnIndex(String conversationId, String executionId) {
        ChatMessageEntity current = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessageEntity>()
                .select(ChatMessageEntity::getTurnIndex)
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .eq(ChatMessageEntity::getExecutionId, executionId)
                .isNotNull(ChatMessageEntity::getTurnIndex)
                .orderByDesc(ChatMessageEntity::getTurnIndex)
                .last("LIMIT 1"));
        return current != null ? current.getTurnIndex() : null;
    }

    public List<ChatMessage> loadExecutionForExtraction(String conversationId, String executionId) {
        List<ChatMessageEntity> entries = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .eq(ChatMessageEntity::getExecutionId, executionId)
                .eq(ChatMessageEntity::getArchiveEntry, true)
                .orderByDesc(ChatMessageEntity::getMessageIndex)
                .last("LIMIT 100"));
        return entries.reversed().stream()
                .filter(entry -> !"USER".equals(entry.getMessageType()) || StringUtils.hasText(entry.getAuthoredText()))
                .map(entry -> "USER".equals(entry.getMessageType())
                        ? UserMessage.from(entry.getAuthoredText()) : toLangChain4jMessage(entry))
                .toList();
    }

    public List<ChatMessage> loadAuthoredExecutionInputs(String conversationId, String executionId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                        .select(ChatMessageEntity::getAuthoredText)
                        .eq(ChatMessageEntity::getConversationId, conversationId)
                        .eq(ChatMessageEntity::getExecutionId, executionId)
                        .eq(ChatMessageEntity::getArchiveEntry, true)
                        .eq(ChatMessageEntity::getMessageType, "USER")
                        .isNotNull(ChatMessageEntity::getAuthoredText)
                        .orderByAsc(ChatMessageEntity::getMessageIndex)).stream()
                .map(ChatMessageEntity::getAuthoredText)
                .filter(StringUtils::hasText)
                .<ChatMessage>map(UserMessage::from)
                .toList();
    }

    public record ArchivedMessage(ChatMessage message, String authoredText) {
    }

    private static final int MAX_VERSION_CAS_ATTEMPTS = 3;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String conversationId = memoryId.toString();
        AgentExecution messageOwner = resolveMessageOwner(conversationId);
        if (messageOwner == null) {
            throw new IllegalStateException("未找到对话所属执行记录");
        }

        // 在修改数据库前转换完整的窗口视图。序列化或写入失败会回滚整个更新。
        List<ChatMessageEntity> entities = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            entities.add(fromLangChain4jMessage(messages.get(i), messageOwner, conversationId, i));
        }
        for (int attempt = 1; attempt <= MAX_VERSION_CAS_ATTEMPTS; attempt++) {
            int expectedVersion = messageOwner.getChatVersion() != null ? messageOwner.getChatVersion() : 0;
            if (agentExecutionMapper.casIncrementChatVersion(messageOwner.getId(), expectedVersion) == 1) {
                deleteMessages(memoryId);
                for (ChatMessageEntity entity : entities) {
                    if (chatMessageMapper.insert(entity) != 1) {
                        throw new IllegalStateException("写入对话消息失败");
                    }
                }
                log.debug("更新 PostgreSQL 对话历史: conversationId={}, count={}, attempt={}",
                        conversationId, entities.size(), attempt);
                return;
            }
            AgentExecution refreshedOwner = agentExecutionMapper.selectById(messageOwner.getId());
            if (refreshedOwner == null) {
                throw new IllegalStateException("对话所属执行记录已移除");
            }
            messageOwner = refreshedOwner;
        }
        throw new IllegalStateException("对话消息版本冲突");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteMessages(Object memoryId) {
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, memoryId.toString()));
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

        if (entity.getMessagePayload() != null) {
            return restoreStructuredMessage(entity.getMessagePayload());
        }

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
            default -> throw new IllegalStateException("未知的持久化对话消息类型");
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
            entity.setTextContent(ChatMessageText.textOf(um));
            if (!um.hasSingleText() || um.name() != null || !um.attributes().isEmpty()) {
                entity.setMessagePayload(serializeStructuredMessage(um));
            }
        } else if (msg instanceof AiMessage am) {
            entity.setMessageType("AI");
            entity.setTextContent(am.text());
            // 工具调用请求序列化到 toolCalls 字段
            if (am.hasToolExecutionRequests() && am.toolExecutionRequests() != null) {
                var toolCallMaps = am.toolExecutionRequests().stream()
                        .map(req -> {
                            Map<String, Object> map = new HashMap<>();
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
            entity.setTextContent(ChatMessageText.textOf(trm));
            if (!trm.hasSingleText() || Boolean.TRUE.equals(trm.isError()) || !trm.attributes().isEmpty()) {
                entity.setMessagePayload(serializeStructuredMessage(trm));
            }
            entity.setToolCallId(trm.id());
            entity.setToolName(trm.toolName());
        } else {
            throw new IllegalArgumentException("不支持持久化该对话消息类型");
        }

        return entity;
    }

    private byte[] serializeStructuredMessage(ChatMessage message) {
        try {
            return ChatMessageSerializer.messageToJson(message).getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            log.warn("编码对话消息失败: errorType={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("无法编码对话消息");
        }
    }

    private ChatMessage restoreStructuredMessage(byte[] payload) {
        try {
            return ChatMessageDeserializer.messageFromJson(new String(payload, StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            log.warn("解码对话消息失败: errorType={}", exception.getClass().getSimpleName());
            throw new IllegalStateException("无法解码持久化对话消息");
        }
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
