package com.schemaplexai.service.agent.execution;

import com.schemaplexai.model.vo.agent.AgentContextBudgetSnapshotVO;

/**
 * Agent 上下文预算服务
 */
public interface AgentContextBudgetService {

    AgentContextBudgetSnapshotVO buildSnapshot(AgentExecutionContext context, SandboxPolicy sandboxPolicy);
}
