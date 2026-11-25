package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.enums.AgentTypeEnum;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
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

    @Override
    public CompletableFuture<AgentExecutionResult> recover(AgentExecutionContext context) {
        return agentExecutionEngine.recover(context);
    }

    @Override
    public CompletableFuture<AgentExecutionResult> resume(Agent agent, AgentExecution execution,
                                                          AgentExecutionContext context, AgentExecutionInputDTO input) {
        // 直接使用编排器构建的上下文（含 sandboxPolicy/executionMode），恢复路径不回退默认策略
        return agentExecutionEngine.resume(context, input);
    }
}
