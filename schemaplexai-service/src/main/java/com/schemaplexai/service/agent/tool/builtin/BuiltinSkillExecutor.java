package com.schemaplexai.service.agent.tool.builtin;

import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;

import java.util.Map;

/**
 * 内置 Skill 执行器接口
 * 用于实现平台内置的高频 Skill 功能，如 Excel 生成、思维导图等
 */
public interface BuiltinSkillExecutor {

    /**
     * 获取 Skill 代码
     */
    String getSkillCode();

    /**
     * 执行 Skill
     *
     * @param tenantId 租户 ID
     * @param agentId  Agent ID
     * @param toolCall 工具调用请求
     * @param config   Skill 配置（从 sf_agent_tool_config 读取）
     * @return 执行结果
     */
    ToolResult execute(String tenantId, String agentId, ToolCall toolCall, Map<String, Object> config);
}
