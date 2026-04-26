package com.schemaplexai.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.R;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.ImageGenerationService;
import com.schemaplexai.service.ai.MultimodalMessageBuilder;
import com.schemaplexai.service.chat.DirectChatStreamRequest;
import com.schemaplexai.service.chat.DirectChatStreamService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 直接模型对话控制器
 */
@Slf4j
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
@Tag(name = "直接模型对话")
public class DirectChatController {

    private final AiModelMapper aiModelMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final LangChain4jModelFactory modelFactory;
    private final DirectChatStreamService directChatStreamService;
    private final MultimodalMessageBuilder multimodalMessageBuilder;
    private final ImageGenerationService imageGenerationService;

    @PostMapping("/direct")
    @Operation(summary = "直接调用模型进行对话")
    public R<DirectChatResponse> directChat(@Valid @RequestBody DirectChatRequest request) {
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

        List<ChatMessageEntity> historyEntities = loadHistoryEntities(conversationId);
        List<ChatMessage> messages = new ArrayList<>();
        historyEntities.stream()
                .map(this::toChatMessage)
                .filter(java.util.Objects::nonNull)
                .forEach(messages::add);

        String runtimeInstruction = buildRuntimeInstruction(request);
        if (StringUtils.hasText(runtimeInstruction)) {
            messages.add(SystemMessage.from(runtimeInstruction));
        }
        AiModelConfig modelConfig = AiModelConfig.from(aiModel);
        messages.add(multimodalMessageBuilder.build(
                request.getUserMessage().trim(), request.getAttachmentIds(), modelConfig.isMultimodal()));

        ChatResponse response = modelFactory.getOrCreate(modelConfig)
                .chat(ChatRequest.builder().messages(messages).build());
        String reply = response.aiMessage() != null ? response.aiMessage().text() : "";

        persistConversation(
                conversationId,
                aiModel,
                historyEntities.size(),
                runtimeInstruction,
                request.getUserMessage().trim(),
                reply
        );

        return R.ok(new DirectChatResponse(conversationId, aiModel.getId(), aiModel.getName(), reply, LocalDateTime.now()));
    }

    @PostMapping(value = "/direct/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式直接调用模型进行对话")
    public SseEmitter directChatStream(@Valid @RequestBody DirectChatStreamRequest request) {
        return directChatStreamService.streamChat(request);
    }

    @PostMapping("/images/generations")
    @Operation(summary = "调用生图模型生成图片")
    public R<ImageGenerationService.ImageGenerationResult> generateImage(
            @Valid @RequestBody ImageGenerationService.ImageGenerationRequest request) {
        return R.ok(imageGenerationService.generate(request));
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

    private String buildRuntimeInstruction(DirectChatRequest request) {
        List<String> sections = new ArrayList<>();
        if (StringUtils.hasText(request.getReasoningStrength())) {
            switch (request.getReasoningStrength().trim().toLowerCase()) {
                case "high" -> sections.add("推理强度要求：请进行深度分析，覆盖边界条件、风险与替代方案。");
                case "low" -> sections.add("推理强度要求：请简洁直接回答，不展开冗长推理。");
                default -> {
                    // medium 保持默认行为。
                }
            }
        }
        if (StringUtils.hasText(request.getOutputFormat())) {
            switch (request.getOutputFormat().trim().toLowerCase()) {
                case "markdown" -> sections.add("输出格式要求：请使用 Markdown 输出。");
                case "plain_text" -> sections.add("输出格式要求：请使用纯文本输出，不使用 Markdown 标记。");
                case "structured_json" -> sections.add("输出格式要求：请仅输出 JSON 对象，不要输出额外解释。");
                default -> {
                    // 未识别格式保持兼容。
                }
            }
        }
        return String.join("\n\n", sections).trim();
    }

    private void persistConversation(String conversationId,
                                     AiModel aiModel,
                                     int startIndex,
                                     String runtimeInstruction,
                                     String userMessage,
                                     String reply) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        int currentIndex = startIndex;
        if (StringUtils.hasText(runtimeInstruction)) {
            chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex++, "SYSTEM", runtimeInstruction));
        }
        chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex++, "USER", userMessage));
        chatMessageMapper.insert(buildEntity(conversationId, aiModel, tenantId, currentIndex, "AI", reply));
    }

    private ChatMessageEntity buildEntity(String conversationId,
                                          AiModel aiModel,
                                          String tenantId,
                                          int messageIndex,
                                          String messageType,
                                          String textContent) {
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

    @Data
    public static class DirectChatRequest {

        @NotBlank(message = "模型不能为空")
        private String modelId;

        @NotBlank(message = "消息不能为空")
        private String userMessage;

        private String conversationId;

        private List<String> attachmentIds;

        private String reasoningStrength;

        private String outputFormat;
    }

    @Data
    @RequiredArgsConstructor
    public static class DirectChatResponse {

        private final String conversationId;
        private final String modelId;
        private final String modelName;
        private final String reply;
        private final LocalDateTime createdAt;
    }
}
