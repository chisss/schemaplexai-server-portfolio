package com.schemaplexai.service.ai;

import lombok.Builder;
import lombok.Data;

/**
 * AI 模型响应结果
 */
@Data
@Builder
public class ChatResponse {

    /** 模型返回的文本内容 */
    private String content;

    /** 输入 Token 数 */
    private long inputTokens;

    /** 输出 Token 数 */
    private long outputTokens;

    /**
     * 停止原因: end_turn / stop / max_tokens / error
     * end_turn / stop → 正常完成，没有工具调用
     * max_tokens      → 输出被截断
     * error           → 调用失败
     */
    private String stopReason;

    /** 是否正常完成（非截断、非错误） */
    private boolean success;

    /** 错误信息（success=false 时填充） */
    private String errorMessage;

    // ---- 工厂方法 ----

    public static ChatResponse success(String content, long inputTokens, long outputTokens, String stopReason) {
        return ChatResponse.builder()
                .content(content)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .stopReason(stopReason)
                .success(true)
                .build();
    }

    public static ChatResponse error(String errorMessage) {
        return ChatResponse.builder()
                .success(false)
                .errorMessage(errorMessage)
                .stopReason("error")
                .build();
    }
}
