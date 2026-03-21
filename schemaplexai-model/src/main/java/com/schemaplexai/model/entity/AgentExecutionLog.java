package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 执行日志表实体
 */
@Data
@TableName(value = "sf_agent_execution_log")
public class AgentExecutionLog implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenantId;

    private String agentId;

    /** 关联执行 ID */
    private String executionId;

    /** 日志级别: DEBUG/INFO/WARN/ERROR */
    private String logLevel;

    /** 日志类型: EXECUTION_START/ROUND_START/AI_RESPONSE/TOOL_CALL/TOOL_RESULT/CONTEXT_COLLECTED/EXECUTION_COMPLETED/EXECUTION_FAILED */
    private String logType;

    /** Agentic Loop 轮次编号 */
    private Integer roundNum;

    /** 工具调用名称 */
    private String toolName;

    /** 日志内容 */
    private String content;

    /** 本条日志产生的 Token 增量 */
    private Integer tokenDelta;

    /** 本条日志距执行开始的累计耗时（毫秒） */
    private Integer elapsedMs;

    private LocalDateTime createdAt;
}
