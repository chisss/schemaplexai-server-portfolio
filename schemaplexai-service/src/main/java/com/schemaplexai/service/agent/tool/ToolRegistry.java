package com.schemaplexai.service.agent.tool;

import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.service.agent.tool.model.ToolResult;

import java.util.List;

/**
 * 工具注册表
 */
public interface ToolRegistry {

    List<ToolDefinition> listEnabledTools(String tenantId, String agentId);

    List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls);
}
