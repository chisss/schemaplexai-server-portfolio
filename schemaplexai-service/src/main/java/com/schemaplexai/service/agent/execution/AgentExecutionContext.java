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

    /** 是否启用流式输出（当前阶段预留） */
    private boolean stream;
}
