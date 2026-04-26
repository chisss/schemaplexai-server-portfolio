package com.schemaplexai.model.dto.agent;

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

    /** 审批决策: approve / deny / approve_always（审批场景使用） */
    private String approvalDecision;

    /** 审批关联的工具编码（审批场景使用） */
    private String toolCode;

    /** 审批关联的工具命令/参数摘要（approve_always 时用于匹配模式） */
    private String toolCommand;

    /** 扩展参数（可选） */
    private Map<String, Object> options;
}
