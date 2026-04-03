package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionEngine;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoloAgentRuntimeStrategyTest {

    @Test
    void shouldResumeExecutionWithResolvedSandboxPolicy() {
        AgentExecutionEngine agentExecutionEngine = mock(AgentExecutionEngine.class);
        SandboxPolicyResolver sandboxPolicyResolver = mock(SandboxPolicyResolver.class);
        SoloAgentRuntimeStrategy strategy = new SoloAgentRuntimeStrategy(agentExecutionEngine, sandboxPolicyResolver);

        Agent agent = new Agent();
        agent.setAiModelType("chat");
        agent.setAiModelGroupId("group-1");

        AgentExecution execution = new AgentExecution();
        execution.setId("exec-1");
        execution.setAgentId("agent-1");
        execution.setTenantId("tenant-1");
        execution.setInputPrompt("请继续修复");
        execution.setAiModel("gpt-4.1");
        execution.setInputContext(Map.of("spec", "solo-resume"));
        execution.setConversationId("conv-1");
        execution.setRuntimeEngine("solo_langchain4j");

        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("管理员已确认继续");

        SandboxPolicy sandboxPolicy = SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .build();
        when(sandboxPolicyResolver.resolve(eq(agent), any(AgentExecutionContext.class))).thenReturn(sandboxPolicy);
        when(agentExecutionEngine.resume(any(AgentExecutionContext.class), eq(input)))
                .thenReturn(CompletableFuture.completedFuture(AgentExecutionResult.builder().status("running").build()));

        strategy.resume(agent, execution, input);

        ArgumentCaptor<AgentExecutionContext> contextCaptor = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(agentExecutionEngine).resume(contextCaptor.capture(), eq(input));
        AgentExecutionContext context = contextCaptor.getValue();
        assertThat(context.getExecutionId()).isEqualTo("exec-1");
        assertThat(context.getAgentId()).isEqualTo("agent-1");
        assertThat(context.getTenantId()).isEqualTo("tenant-1");
        assertThat(context.getConversationId()).isEqualTo("conv-1");
        assertThat(context.getRuntimeEngine()).isEqualTo("solo_langchain4j");
        assertThat(context.getSandboxPolicy()).isSameAs(sandboxPolicy);
    }
}
