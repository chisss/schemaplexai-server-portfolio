package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对话消息实体
 * 作为 LangChain4J ChatMemoryStore 的持久化后端
 */
@Data
@TableName(value = "sf_chat_message", autoResultMap = true)
public class ChatMessageEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID */
    private String tenantId;

    /** 会话标识（同一多轮对话共享） */
    private String conversationId;

    /** 关联的执行记录 ID（可空） */
    private String executionId;

    /** 关联的 Agent ID */
    private String agentId;

    /** 消息类型: SYSTEM, USER, AI, TOOL_EXECUTION_RESULT */
    private String messageType;

    /** 消息文本内容 */
    private String textContent;

    /** 结构化消息的 UTF-8 JSON 字节，文本列仍可单独读取。 */
    @ToString.Exclude
    private byte[] messagePayload;

    /** AI 消息中的工具调用请求（JSON 数组） */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> toolCalls;

    /** 工具执行结果消息的 callId */
    private String toolCallId;

    /** 工具执行结果消息的工具名 */
    private String toolName;

    /** 该消息的 token 数（估算） */
    private Integer tokenCount;

    /** 消息在会话中的顺序索引 */
    private Integer messageIndex;

    /** 运行器直接追加的原始消息，空值代表历史写入方式。 */
    private Boolean archiveEntry;

    /** 用户输入原文，排除运行器合成内容和附件文本。 */
    @ToString.Exclude
    private String authoredText;

    /** 对话轮次索引（用于回滚） */
    private Integer turnIndex;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
