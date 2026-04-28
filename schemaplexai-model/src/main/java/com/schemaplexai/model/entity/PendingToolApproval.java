package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 待审批工具调用快照
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "sf_pending_tool_approval", autoResultMap = true)
public class PendingToolApproval extends BaseEntity {

    /** 关联执行 ID */
    private String executionId;

    /** Agent ID */
    private String agentId;

    /** 会话 ID */
    private String conversationId;

    /** Agentic Loop 轮次 */
    private Integer roundNum;

    /** 模型工具调用 ID */
    private String toolCallId;

    /** 工具编码 */
    private String toolCode;

    /** 工具展示名称 */
    private String toolName;

    /** 工具参数快照 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> toolArguments;

    /** 工具参数原始文本 */
    private String toolArgumentsText;

    /** 工具 IO 类型 */
    private String ioType;

    /** 风险等级 */
    private String riskLevel;

    /** 执行模式 */
    private String executionMode;

    /** 决策状态: pending/approved/denied/edited/expired/cancelled */
    private String decisionStatus;

    /** 用户决策: approve/approve_always/deny/edit */
    private String decision;

    /** 决策原因 */
    private String decisionReason;

    /** 决策人 */
    private String decidedBy;

    /** 决策时间 */
    private LocalDateTime decidedAt;

    /** 过期时间 */
    private LocalDateTime expiresAt;
}
