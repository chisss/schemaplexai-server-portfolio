package com.schemaplexai.model.dto.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.util.Map;

/**
 * Human-in-Loop 输入 DTO
 * <p>支持普通文本输入和审批决策两种场景</p>
 */
@Data
public class AgentExecutionInputDTO {

    /** 用户输入文本 */
    private String message;

    /** 审批决策: approve / deny / approve_always / edit（审批场景使用） */
    private String approvalDecision;

    /** 审批关联的工具编码（审批场景使用） */
    private String toolCode;

    /** 审批关联的工具命令/参数摘要（approve_always 时用于匹配模式） */
    private String toolCommand;

    /** 待审批工具调用 ID */
    private String approvalId;

    /** 编辑后的工具参数（approvalDecision=edit 时使用） */
    private Map<String, Object> editedArguments;

    /** 审批或拒绝原因 */
    private String decisionReason;

    /** 扩展参数（可选） */
    private Map<String, Object> options;

    /** 管理员恢复风控事件时，指定原暂停的 Team 成员。 */
    @JsonIgnore
    private String securityPausedChildExecutionId;

    /** 管理员复核在首次模型调用前暂停的任务。 */
    @JsonIgnore
    private boolean resumeBeforeFirstRound;

    /** 仅 true 会收紧原执行，禁用两类长期记忆。 */
    private Boolean temporaryChat;

    /** 仅 false 会收紧原执行，关闭用户长期记忆写入。 */
    private Boolean memoryWriteEnabled;
}
