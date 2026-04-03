package com.schemaplexai.service.agent.tool;

import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import com.schemaplexai.common.model.ToolResult;

import java.util.List;

/**
 * 工具注册表
 */
public interface ToolRegistry {

    List<ToolDefinition> listEnabledTools(String tenantId, String agentId);

    List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls);

    default List<ToolDefinition> listEnabledTools(String tenantId, String agentId,
                                                  List<AgentToolBinding> overrideBindings,
                                                  SandboxPolicy sandboxPolicy) {
        return listEnabledTools(tenantId, agentId);
    }

    default List<ToolResult> executeAll(String tenantId, String agentId, List<ToolCall> toolCalls,
                                        List<AgentToolBinding> overrideBindings,
                                        SandboxPolicy sandboxPolicy) {
        return executeAll(tenantId, agentId, toolCalls);
    }
}
