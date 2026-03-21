package com.schemaplexai.service.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI 对话消息（多轮对话历史单元）
 * role: user / assistant
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    /** 消息角色: user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }
}
