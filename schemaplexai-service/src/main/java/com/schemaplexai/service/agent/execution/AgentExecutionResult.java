package com.schemaplexai.service.agent.execution;

import lombok.Builder;
import lombok.Data;

/**
 * Agent 执行结果 POJO
 * 由 AgentExecutionEngine 执行完成后返回
 */
@Data
@Builder
public class AgentExecutionResult {

    /** 最终状态：completed / failed / stopped */
    private String status;

    /** 最终输出文本 */
    private String outputResult;

    /** 会话 ID */
    private String conversationId;

    /** 错误信息（status=failed 时填充） */
    private String errorMessage;

    /** 总输入 Token */
    private long tokenInput;

    /** 总输出 Token */
    private long tokenOutput;

    /** 执行轮次 */
    private int rounds;
}
