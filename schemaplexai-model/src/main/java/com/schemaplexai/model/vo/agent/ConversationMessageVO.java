package com.schemaplexai.model.vo.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息 VO（用于查询对话历史）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessageVO {

    /** 消息类型: SYSTEM / USER / AI / TOOL_EXECUTION_RESULT */
    private String messageType;

    /** 文本内容 */
    private String textContent;

    /** 工具调用 ID（仅 TOOL_EXECUTION_RESULT 类型） */
    private String toolCallId;

    /** 工具名称（仅 TOOL_EXECUTION_RESULT 类型） */
    private String toolName;

    /** 消息序号 */
    private Integer messageIndex;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
