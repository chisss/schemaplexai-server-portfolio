package com.schemaplexai.service.agent.hook;

import com.schemaplexai.common.model.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.Builder;
import lombok.Getter;

/**
 * Hook 执行上下文，携带当前阶段的可读/可写数据
 */
@Getter
@Builder
public class AgentHookContext {

    /** 执行 ID */
    private final String executionId;
    /** Agent ID */
    private final String agentId;
    /** 租户 ID */
    private final String tenantId;
    /** 当前轮次 */
    private final int round;

    // BEFORE_MODEL_CALL / AFTER_MODEL_CALL
    private final ChatRequest  chatRequest;
    private final ChatResponse chatResponse;

    // BEFORE_TOOL_EXECUTE / AFTER_TOOL_EXECUTE
    private final ToolExecutionRequest toolRequest;
    private final ToolResult           toolResult;
    private final String               toolCode;

    // ON_LOOP_COMPLETE
    private final String finalContent;
    private final long   totalTokenInput;
    private final long   totalTokenOutput;
    private final long   elapsedMs;
}
