package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.handler.AgentConfigHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复路径上下文契约（审计 H2）：编排器构建的上下文必须携带
 * executionMode 恢复值与解析后的 sandboxPolicy，并原样传给策略。
 */
class AgentRuntimeOrchestratorTest {

    @Test
    void shouldInjectSandboxPolicyIntoResumeContextAndPassToStrategy() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        AgentRuntimeStrategyFactory strategyFactory = mock(AgentRuntimeStrategyFactory.class);
        SandboxPolicyResolver sandboxPolicyResolver = mock(SandboxPolicyResolver.class);
        AgentConfigHandler agentConfigHandler = mock(AgentConfigHandler.class);
        AgentRuntimeStrategy strategy = mock(AgentRuntimeStrategy.class);
        AgentRuntimeOrchestrator orchestrator = new AgentRuntimeOrchestrator(
                agentMapper, agentExecutionMapper, strategyFactory, sandboxPolicyResolver, agentConfigHandler);

        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setAgentType("solo");
        agent.setAiModelType("model");
        when(agentMapper.selectById("agent-1")).thenReturn(agent);

        AgentExecution execution = new AgentExecution();
        execution.setId("exec-1");
        execution.setAgentId("agent-1");
        execution.setTenantId("tenant-1");
        execution.setCreatedBy("user-1");
        execution.setInputPrompt("继续修复");
        execution.setAiModel("gpt-4.1");
        execution.setConversationId("conv-1");
        execution.setExecutionMode("plan");
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(execution);

        SandboxPolicy sandboxPolicy = SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .build();
        when(sandboxPolicyResolver.resolve(eq(agent), any(AgentExecutionContext.class))).thenReturn(sandboxPolicy);
        when(sandboxPolicyResolver.toSnapshot(any(SandboxPolicy.class))).thenReturn(Collections.emptyMap());
        when(agentConfigHandler.loadConfigs("agent-1")).thenReturn(Collections.emptyList());
        when(strategyFactory.getStrategy("solo")).thenReturn(strategy);
        when(strategy.resume(eq(agent), eq(execution), any(AgentExecutionContext.class), any(AgentExecutionInputDTO.class)))
                .thenReturn(CompletableFuture.completedFuture(AgentExecutionResult.builder().status("running").build()));

        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("管理员已确认继续");

        orchestrator.resume("exec-1", input);

        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(strategy).resume(eq(agent), eq(execution), contextCaptor.capture(), eq(input));
        AgentExecutionContext context = contextCaptor.getValue();
        // 恢复路径核心契约：策略不丢失（H2 缺口回归断言）
        assertThat(context.getExecutionMode()).isEqualTo("plan");
        assertThat(context.getSandboxPolicy()).isSameAs(sandboxPolicy);
        verify(agentExecutionMapper).updateById(any(AgentExecution.class));
    }
}
