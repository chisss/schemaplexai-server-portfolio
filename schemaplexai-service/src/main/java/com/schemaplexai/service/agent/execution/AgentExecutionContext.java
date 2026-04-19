package com.schemaplexai.service.agent.execution;

import com.schemaplexai.model.entity.AgentToolBinding;
import lombok.Builder;
import lombok.Data;

import java.util.List;
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

    /** 父执行 ID（Team 子执行场景） */
    private String parentExecutionId;

    /** Team Agent ID（团队场景下用于注入团队共享上下文） */
    private String teamAgentId;

    /** Team 成员 ID */
    private String teamMemberId;

    /** Team 成员角色名 */
    private String teamMemberRoleName;

    /** Team 成员角色类型 */
    private String teamMemberRoleType;

    /** 运行时引擎编码 */
    private String runtimeEngine;

    /** 附加上下文变量（键值对） */
    private Map<String, Object> inputContext;

    /** 运行时附加系统上下文 */
    private List<String> additionalSystemContexts;

    /** 运行时工具绑定覆盖 */
    private List<AgentToolBinding> overrideToolBindings;

    /** 执行期沙箱策略 */
    private SandboxPolicy sandboxPolicy;

    /** 会话标识（前端传入或自动生成，支持多轮对话） */
    private String conversationId;

    /** 附件对象 ID 列表 */
    private List<String> attachmentIds;

    /** 推理强度：low / medium / high */
    private String reasoningStrength;

    /** 直接技能编码 */
    private String skillCode;

    /** 输出格式覆盖 */
    private String outputFormat;

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
