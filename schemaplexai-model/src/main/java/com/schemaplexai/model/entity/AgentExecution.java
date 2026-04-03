package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 执行记录实体
 */
@Data
@TableName(value = "sf_agent_execution", autoResultMap = true)
public class AgentExecution implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    @TableField(fill = FieldFill.INSERT)
    private String tenantId;

    private String agentId;

    /** 关联任务 ID（可选） */
    private String taskId;

    /** 关联 Spec ID（可选） */
    private String specId;

    /** 会话标识，支持多轮对话 */
    private String conversationId;

    /** 运行时引擎 */
    private String runtimeEngine;

    /** 父执行 ID */
    private String parentExecutionId;

    /** Team 成员 ID */
    private String teamMemberId;

    /** LangGraph4J 线程 ID */
    private String graphThreadId;

    /** Checkpoint 命名空间 */
    private String checkpointNamespace;

    /** 执行指令（可选补充说明） */
    private String instruction;

    /** 输入 Prompt */
    private String inputPrompt;

    /** 使用模型，列名 ai_model */
    @TableField("ai_model")
    private String aiModel;

    /** 状态: queued/running/completed/failed/stopped */
    private String status;

    /** 输入上下文数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> inputContext;

    /** 本次执行的沙箱策略快照 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> sandboxPolicySnapshot;

    /** 输出结果 */
    private String outputResult;

    /** 输入 Token 数 */
    private Long tokenInput;

    /** 输出 Token 数 */
    private Long tokenOutput;

    /** 错误信息 */
    private String errorMessage;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 完成时间 */
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT)
    private String createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}
