package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Solo Agent 运行时策略
 */
@Component
@RequiredArgsConstructor
public class SoloAgentRuntimeStrategy implements AgentRuntimeStrategy {

    private final AgentExecutionEngine agentExecutionEngine;

    @Override
    public AgentTypeEnum supportType() {
        return AgentTypeEnum.SOLO;
    }

    @Override
    public CompletableFuture<AgentExecutionResult> execute(Agent agent, AgentExecution execution, AgentExecutionContext context) {
        return agentExecutionEngine.execute(context);
    }
}
