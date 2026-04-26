package com.schemaplexai.service.agent.hook.builtin;

import com.schemaplexai.service.agent.hook.AgentHook;
import com.schemaplexai.service.agent.hook.AgentHookContext;
import com.schemaplexai.service.agent.hook.AgentHookType;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 工具执行安全审计 Hook（BEFORE_TOOL_EXECUTE）
 * 在工具执行前记录审计日志，不阻止执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolSecurityAuditHook implements AgentHook {

    private final ToolExecutionLogService toolExecutionLogService;

    @Override
    public String name() { return "tool-security-audit"; }

    @Override
    public AgentHookType type() { return AgentHookType.BEFORE_TOOL_EXECUTE; }

    @Override
    public int order() { return 10; }

    @Override
    public boolean execute(AgentHookContext context) {
        if (context.getToolRequest() == null) return true;
        log.debug("[SecurityAuditHook] executionId={} agentId={} tool={} round={}",
                context.getExecutionId(), context.getAgentId(),
                context.getToolRequest().name(), context.getRound());
        return true;
    }
}
