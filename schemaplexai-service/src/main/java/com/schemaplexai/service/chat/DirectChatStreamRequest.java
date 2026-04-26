package com.schemaplexai.service.chat;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 流式直聊请求参数
 */
@Data
public class DirectChatStreamRequest {

    @NotBlank(message = "模型不能为空")
    private String modelId;

    @NotBlank(message = "消息不能为空")
    private String userMessage;

    private String conversationId;

    private List<String> attachmentIds;

    private String reasoningStrength;

    private String outputFormat;
}
