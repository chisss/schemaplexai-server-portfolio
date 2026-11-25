package com.schemaplexai.service.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.List;
import java.util.stream.Collectors;

/** 文本投影只读取显式文本内容，供摘要、日志统计和记忆提取使用。 */
public final class ChatMessageText {

    private ChatMessageText() {
    }

    public static String textOf(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> joinText(user.contents());
            case ToolExecutionResultMessage result -> joinText(result.contents());
            case AiMessage ai -> ai.text() == null ? "" : ai.text();
            case SystemMessage system -> system.text();
            case null, default -> "";
        };
    }

    private static String joinText(List<Content> contents) {
        return contents.stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .collect(Collectors.joining("\n"));
    }
}
