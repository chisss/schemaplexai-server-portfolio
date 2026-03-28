package com.schemaplexai.service.agent.tool.executor;

import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;

/**
 * 工具执行器 SPI
 */
public interface ToolExecutor {

    /**
     * 工具来源类型：builtin / skill / mcp
     */
    String sourceType();

    /**
     * 执行工具调用
     */
    ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall);
}
