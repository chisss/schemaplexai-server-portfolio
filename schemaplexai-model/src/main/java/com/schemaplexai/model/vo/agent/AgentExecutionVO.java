package com.schemaplexai.model.vo.agent;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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

    /** 会话 ID */
    private String conversationId;

    /** 运行时引擎 */
    private String runtimeEngine;

    /** 输入 Token 数 */
    private Long tokenInput;

    /** 输出 Token 数 */
    private Long tokenOutput;

    /** 错误信息 */
    private String errorMessage;

    /** 最终输出结果 */
    private String outputResult;

    /** 父执行 ID */
    private String parentExecutionId;

    /** Team 成员 ID */
    private String teamMemberId;

    /** Team 成员角色名 */
    private String teamMemberRoleName;

    /** LangGraph4J 线程 ID */
    private String graphThreadId;

    /** Checkpoint 命名空间 */
    private String checkpointNamespace;

    /** 沙箱策略快照 */
    private Map<String, Object> sandboxPolicySnapshot;

    /** 最近一次安全决策 */
    private String securityDecision;

    /** 最近一次安全提示 */
    private String securityMessage;

    /** 最近一次安全链路 ID */
    private String securityTraceId;

    /** 最近一次安全事件 ID */
    private String securityIncidentId;

    /** 最近一次质量任务 ID */
    private String qualityTaskId;

    /** 最近一次质量任务状态 */
    private String qualityTaskStatus;

    /** 最近一次质量摘要 */
    private String qualitySummary;

    /** 质量偏离总数 */
    private Integer qualityDeviationCount;

    /** 质量预警数 */
    private Integer qualityWarningCount;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    /** 执行日志（getExecution 详情时附带） */
    private List<AgentExecutionLogVO> logs;

    /** Team 子执行列表（仅 Team 父执行详情时附带） */
    private List<AgentExecutionChildVO> childExecutions;
}
