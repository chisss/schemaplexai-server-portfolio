package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 执行详情 VO
 */
@Data
public class AgentExecutionVO {

    /** 执行 ID */
    private String executionId;

    /** Agent ID */
    private String agentId;

    /** 状态: queued/running/completed/failed/stopped */
    private String status;

    /** 输入指令 */
    private String inputPrompt;

    /** 使用模型 */
    private String model;

    /** 输入 Token 数 */
    private Long tokenInput;

    /** 输出 Token 数 */
    private Long tokenOutput;

    /** 错误信息 */
    private String errorMessage;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 执行日志（getExecution 详情时附带） */
    private List<AgentExecutionLogVO> logs;
}
