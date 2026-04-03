package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Team Agent 子执行摘要
 */
@Data
public class AgentExecutionChildVO {

    /** 子执行 ID */
    private String executionId;

    /** 父执行 ID */
    private String parentExecutionId;

    /** Team 成员 ID */
    private String teamMemberId;

    /** Team 成员角色名 */
    private String teamMemberRoleName;

    /** 执行状态 */
    private String status;

    /** 使用模型 */
    private String model;

    /** 错误信息 */
    private String errorMessage;

    /** 输出结果 */
    private String outputResult;

    /** 子执行日志 */
    private List<AgentExecutionLogVO> logs;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间 */
    private LocalDateTime completedAt;
}
