package com.schemaplexai.service.agent.execution;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Agent 执行上下文 POJO
 * 携带单次执行所需的全部参数，传递给 AgentExecutionEngine
 */
@Data
@Builder
public class AgentExecutionContext {

    /** 执行记录 ID */
    private String executionId;

    /** Agent ID */
    private String agentId;

    /** 租户 ID */
    private String tenantId;

    /** 用户输入的任务描述 */
    private String inputPrompt;

    /** 指定使用的模型 ID（为空时使用 Agent 默认配置） */
    private String model;

    /** 模型绑定类型: model/model_group */
    private String agentModelType;

    /** 当 agentModelType=model_group 时，绑定的模型组 ID */
    private String agentModelGroupId;

    /** 附加上下文变量（键值对） */
    private Map<String, Object> inputContext;

    /** 会话标识（前端传入或自动生成，支持多轮对话） */
    private String conversationId;

    /** 短期记忆最大消息数（默认100） */
    @Builder.Default
    private int maxMessages = 100;

    /** 最大推理轮次（默认50） */
    @Builder.Default
    private int maxRounds = 50;

    /** 每轮允许的最大工具调用数（默认8） */
    @Builder.Default
    private int maxToolCallsPerRound = 8;

    /** 是否启用流式输出（当前阶段预留） */
    private boolean stream;
}
