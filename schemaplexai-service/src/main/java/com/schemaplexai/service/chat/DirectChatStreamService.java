package com.schemaplexai.service.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.MultimodalMessageBuilder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 直聊流式输出服务 — 基于 LangChain4j StreamingChatModel + Spring SseEmitter
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DirectChatStreamService {

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

    private final AiModelMapper aiModelMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final LangChain4jModelFactory modelFactory;
    private final MultimodalMessageBuilder multimodalMessageBuilder;
    private final ObjectMapper objectMapper;

    /**
     * 创建流式直聊 SSE 连接
     */
    public SseEmitter streamChat(DirectChatStreamRequest request) {
        AiModel aiModel = aiModelMapper.selectOne(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getId, request.getModelId())
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .last("limit 1"));
        if (aiModel == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "指定模型不存在或未启用");
        }

        String conversationId = StringUtils.hasText(request.getConversationId())
                ? request.getConversationId()
                : UUID.randomUUID().toString().replace("-", "");

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        String tenantId = SecurityUtil.getCurrentTenantId();

        List<ChatMessageEntity> historyEntities = loadHistoryEntities(conversationId);
        List<ChatMessage> messages = new ArrayList<>();
        historyEntities.stream()
                .map(this::toChatMessage)
                .filter(Objects::nonNull)
                .forEach(messages::add);

        AiModelConfig modelConfig = AiModelConfig.from(aiModel, request.getReasoningStrength());
        String runtimeInstruction = buildRuntimeInstruction(request);
        if (StringUtils.hasText(runtimeInstruction)) {
            messages.add(SystemMessage.from(runtimeInstruction));
        }
        messages.add(multimodalMessageBuilder.build(
                request.getUserMessage().trim(), request.getAttachmentIds(), modelConfig.isMultimodal()));

        sendEvent(emitter, AgentExecutionEventTypeEnum.STREAM_START, conversationId, null);

        StreamingChatModel streamingModel = modelFactory.getOrCreateStreaming(modelConfig);
        StringBuilder fullResponse = new StringBuilder();
        StringBuilder thinkingBuffer = new StringBuilder();
        int historySize = historyEntities.size();

        streamingModel.chat(
                ChatRequest.builder().messages(messages).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullResponse.append(partialResponse);
                        sendEvent(emitter, AgentExecutionEventTypeEnum.STREAM_CHUNK,
                                conversationId, Map.of("content", partialResponse));
                    }

                    @Override
                    public void onPartialThinking(PartialThinking partialThinking) {
                        if (partialThinking != null && partialThinking.text() != null) {
                            thinkingBuffer.append(partialThinking.text());
                            sendEvent(emitter, AgentExecutionEventTypeEnum.THINKING,
                                    conversationId, Map.of("content", partialThinking.text()));
                        }
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        try {
                            persistConversation(conversationId, aiModel, tenantId,
                                    historySize, runtimeInstruction,
                                    request.getUserMessage().trim(), fullResponse.toString());
                            sendEvent(emitter, AgentExecutionEventTypeEnum.STREAM_END,
                                    conversationId, Map.of("conversationId", conversationId));
                            emitter.complete();
                        } catch (Exception e) {
                            log.error("流式直聊持久化失败: conversationId={}", conversationId, e);
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("流式直聊模型调用失败: conversationId={}", conversationId, error);
                        sendEvent(emitter, AgentExecutionEventTypeEnum.FAILED,
                                conversationId, Map.of("content", "模型调用失败: " + error.getMessage()));
                        emitter.completeWithError(error);
                    }
                }
        );

        return emitter;
    }
    private void sendEvent(SseEmitter emitter, AgentExecutionEventTypeEnum eventType,
                           String conversationId, Object payload) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", eventType.getCode());
            event.put("conversationId", conversationId);
            event.put("timestamp", Instant.now().toString());
            if (payload != null) {
                event.put("payload", payload);
            }
            emitter.send(SseEmitter.event()
                    .data(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            log.debug("SSE 事件发送失败: eventType={}, error={}", eventType.getCode(), e.getMessage());
        }
    }

    private List<ChatMessageEntity> loadHistoryEntities(String conversationId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getConversationId, conversationId)
                .orderByAsc(ChatMessageEntity::getMessageIndex));
    }

    private ChatMessage toChatMessage(ChatMessageEntity entity) {
        if (entity == null || !StringUtils.hasText(entity.getMessageType())) {
            return null;
        }
        return switch (entity.getMessageType()) {
            case "SYSTEM" -> SystemMessage.from(entity.getTextContent() == null ? "" : entity.getTextContent());
            case "USER" -> UserMessage.from(entity.getTextContent() == null ? "" : entity.getTextContent());
            case "AI" -> AiMessage.from(entity.getTextContent() == null ? "" : entity.getTextContent());
            default -> null;
        };
    }

    private String buildRuntimeInstruction(DirectChatStreamRequest request) {
        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(request.getReasoningStrength())) {
            switch (request.getReasoningStrength().trim().toLowerCase()) {
                case "high" -> sections.add("推理强度要求：请进行深度分析，覆盖边界条件、风险与替代方案。");
                case "low" -> sections.add("推理强度要求：请简洁直接回答，不展开冗长推理。");
                default -> { }
            }
        }
        if (StringUtils.hasText(request.getOutputFormat())) {
            switch (request.getOutputFormat().trim().toLowerCase()) {
                case "markdown" -> sections.add("输出格式要求：请使用 Markdown 输出。");
                case "plain_text" -> sections.add("输出格式要求：请使用纯文本输出，不使用 Markdown 标记。");
                case "structured_json" -> sections.add("输出格式要求：请仅输出 JSON 对象，不要输出额外解释。");
                default -> { }
            }
        }
        return String.join("\n\n", sections).trim();
    }

    private void persistConversation(String conversationId, AiModel aiModel, String tenantId,
                                     int startIndex, String runtimeInstruction,
                                     String userMessage, String reply) {
        int currentIndex = startIndex;
        if (StringUtils.hasText(runtimeInstruction)) {
            chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex++, "SYSTEM", runtimeInstruction));
        }
        chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex++, "USER", userMessage));
        chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex, "AI", reply));
    }

    private ChatMessageEntity buildEntity(String conversationId, AiModel aiModel, String tenantId,
                                          int messageIndex, String messageType, String textContent) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setTenantId(tenantId);
        entity.setConversationId(conversationId);
        entity.setAgentId(aiModel.getId());
        entity.setMessageType(messageType);
        entity.setTextContent(textContent);
        entity.setMessageIndex(messageIndex);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setDeleted(0);
        return entity;
    }
}
