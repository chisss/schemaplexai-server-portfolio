package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复路径上下文由编排器统一构建（含 sandboxPolicy/executionMode），
 * 策略实现只做透传，不得重建上下文（审计 H2）。
 */
class SoloAgentRuntimeStrategyTest {

    @Test
    void shouldPassThroughOrchestratorContextToEngine() {
        AgentExecutionEngine agentExecutionEngine = mock(AgentExecutionEngine.class);
        SoloAgentRuntimeStrategy strategy = new SoloAgentRuntimeStrategy(agentExecutionEngine);

        Agent agent = new Agent();
        AgentExecution execution = new AgentExecution();
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId("exec-1")
                .agentId("agent-1")
                .tenantId("tenant-1")
                .executionMode("plan")
                .build();
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("管理员已确认继续");

        when(agentExecutionEngine.resume(any(AgentExecutionContext.class), eq(input)))
                .thenReturn(CompletableFuture.completedFuture(AgentExecutionResult.builder().status("running").build()));

        strategy.resume(agent, execution, context, input);

        // 上下文原样透传，策略层不再重建（重建会丢失 sandboxPolicy/executionMode）
        verify(agentExecutionEngine).resume(same(context), eq(input));
    }
}
