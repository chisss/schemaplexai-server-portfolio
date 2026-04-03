package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;

import java.util.concurrent.CompletableFuture;

/**
 * Agent 运行时策略
 */
public interface AgentRuntimeStrategy {

    AgentTypeEnum supportType();

    CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context);

    default CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution, AgentExecutionInputDTO input) {
        return CompletableFuture.completedFuture(null);
    }
}
