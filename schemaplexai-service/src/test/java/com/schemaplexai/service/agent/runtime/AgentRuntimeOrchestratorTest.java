package com.schemaplexai.service.agent.runtime;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.execution.AgentAcceptance;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentExecutionResult;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver;
import com.schemaplexai.service.agent.handler.AgentConfigHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    void shouldFreezeDesktopVerificationCommandOnStartAndReuseItAfterAnAdminEdit() {
        Fixture fixture = new Fixture("solo");
        var desktopTarget = Map.<String, Object>of("desktopDeviceId", "device", "desktopWorkspaceId", "workspace");
        fixture.execution.setInputContext(desktopTarget);
        var config = new AgentConfig();
        config.setConfigKey("desktop_required_verification_command");
        config.setConfigValue("mvn clean package");
        when(fixture.configs.loadConfigs("agent")).thenReturn(List.of(config));
        AgentExecutionContext starting = AgentExecutionContext.builder().executionId("exec").agentId("agent")
                .inputContext(desktopTarget).build();

        fixture.runtime.execute(starting);

        assertThat(starting.getDesktopVerificationCommand()).isEqualTo("mvn clean package");
        ArgumentCaptor<AgentExecution> persisted = ArgumentCaptor.forClass(AgentExecution.class);
        verify(fixture.executions).updateById(persisted.capture());
        fixture.execution.setSandboxPolicySnapshot(persisted.getValue().getSandboxPolicySnapshot());
        config.setConfigValue("mvn clean verify");

        AgentExecutionContext resumed = fixture.resume(new AgentExecutionInputDTO());

        assertThat(resumed.getDesktopVerificationCommand()).isEqualTo("mvn clean package");
    }

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
        execution.setMemoryProjectId("project-1");
        execution.setInputPrompt("继续修复");
        execution.setInputContext(Map.of("desktopDeviceId", "device-1", "desktopWorkspaceId", "workspace-1"));
        execution.setAiModel("gpt-4.1");
        execution.setConversationId("conv-1");
        execution.setExecutionMode("plan");
        execution.setSandboxPolicySnapshot(Map.of("stream", true));
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
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(contextCaptor.getValue().getProjectId()).isEqualTo("project-1");
        assertThat(contextCaptor.getValue().getInputContext()).isEqualTo(execution.getInputContext());
        AgentExecutionContext context = contextCaptor.getValue();
        // 恢复路径核心契约：策略不丢失（H2 缺口回归断言）
        assertThat(context.getExecutionMode()).isEqualTo("plan");
        assertThat(context.getSandboxPolicy()).isSameAs(sandboxPolicy);
        assertThat(context.isStream()).isTrue();
        verify(agentExecutionMapper).updateById(any(AgentExecution.class));
    }
    @ParameterizedTest
    @ValueSource(strings = {"solo", "team"})
    void shouldRetainPersistedRestrictionsForApproval(String agentType) {
        Fixture fixture = new Fixture(agentType);
        fixture.execution.setTemporaryChat(true);
        fixture.execution.setMemoryWriteEnabled(false);

        AgentExecutionInputDTO approval = new AgentExecutionInputDTO();
        approval.setApprovalDecision("approve");
        approval.setTemporaryChat(false);
        approval.setMemoryWriteEnabled(true);
        AgentExecutionContext resumed = fixture.resume(approval);

        assertThat(resumed.isTemporaryChat()).isTrue();
        assertThat(resumed.getMemoryWriteEnabled()).isFalse();
        assertThat(resumed.getConversationId()).isEqualTo("conv");
    }

    @Test
    void shouldRestoreDefaultUserWritePolicyForNormalExecution() {
        Fixture fixture = new Fixture("solo");
        fixture.execution.setTemporaryChat(false);
        AgentExecutionContext resumed = fixture.resume(new AgentExecutionInputDTO());
        assertThat(resumed.isTemporaryChat()).isFalse();
        assertThat(resumed.getMemoryWriteEnabled()).isNull();
    }

    @Test
    void shouldKeepExplicitWriteOptOutForNormalExecution() {
        Fixture fixture = new Fixture("solo");
        fixture.execution.setTemporaryChat(false);
        fixture.execution.setMemoryWriteEnabled(false);
        AgentExecutionContext resumed = fixture.resume(new AgentExecutionInputDTO());
        assertThat(resumed.isTemporaryChat()).isFalse();
        assertThat(resumed.getMemoryWriteEnabled()).isFalse();
    }

    @Test
    void shouldRestoreOlderRowsWithConservativePolicyAndOriginalConversation() {
        Fixture fixture = new Fixture("solo");
        AgentExecutionContext resumed = fixture.resume(new AgentExecutionInputDTO());
        assertThat(resumed.isTemporaryChat()).isTrue();
        assertThat(resumed.getMemoryWriteEnabled()).isFalse();
        assertThat(resumed.getConversationId()).isEqualTo("conv");

        ArgumentCaptor<AgentExecution> update = ArgumentCaptor.forClass(AgentExecution.class);
        verify(fixture.executions).updateById(update.capture());
        assertThat(update.getValue().getTemporaryChat()).isTrue();
        assertThat(update.getValue().getMemoryWriteEnabled()).isFalse();
    }

    @Test
    void shouldPreserveStricterTeamParentOnDirectChildResume() {
        Fixture fixture = new Fixture("team");
        fixture.execution.setTemporaryChat(false);
        fixture.execution.setParentExecutionId("parent");
        AgentExecution parent = new AgentExecution();
        parent.setId("parent");
        parent.setMemoryProjectId("parent-project");
        fixture.execution.setMemoryProjectId("unexpected-child-project");
        parent.setTemporaryChat(true);
        parent.setMemoryWriteEnabled(false);
        when(fixture.executions.selectById("parent")).thenReturn(parent);

        AgentExecutionContext resumed = fixture.resume(new AgentExecutionInputDTO());

        assertThat(resumed.isTemporaryChat()).isTrue();
        assertThat(resumed.getMemoryWriteEnabled()).isFalse();
        assertThat(resumed.getProjectId()).isEqualTo("parent-project");
    }

    @Test
    void shouldPersistPolicyForInternalQueuedExecution() {
        Fixture fixture = new Fixture("solo");
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionId("exec").agentId("agent").temporaryChat(true).memoryWriteEnabled(false).build();

        fixture.runtime.execute(context);

        ArgumentCaptor<AgentExecution> update = ArgumentCaptor.forClass(AgentExecution.class);
        verify(fixture.executions).updateById(update.capture());
        assertThat(update.getValue().getTemporaryChat()).isTrue();
        assertThat(update.getValue().getMemoryWriteEnabled()).isFalse();
        verify(fixture.strategy).execute(fixture.agent, fixture.execution, context);
    }

    @Test
    void shouldPersistSynchronousRequestPolicyAtCreation() {
        Fixture fixture = new Fixture("solo");
        when(fixture.executions.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution inserted = invocation.getArgument(0);
            inserted.setId("exec");
            when(fixture.executions.selectById("exec")).thenReturn(inserted);
            return 1;
        });
        when(fixture.strategy.execute(eq(fixture.agent), any(AgentExecution.class), any(AgentExecutionContext.class)))
                .thenReturn(CompletableFuture.completedFuture(AgentExecutionResult.builder().status("completed").build()));
        AgentExecuteDTO dto = new AgentExecuteDTO();
        dto.setTemporaryChat(true);
        dto.setMemoryWriteEnabled(false);

        fixture.runtime.executeSynchronously(fixture.agent, dto);

        ArgumentCaptor<AgentExecution> inserted = ArgumentCaptor.forClass(AgentExecution.class);
        verify(fixture.executions).insert(inserted.capture());
        assertThat(inserted.getValue().getTemporaryChat()).isTrue();
        assertThat(inserted.getValue().getMemoryWriteEnabled()).isFalse();
    }

    @Test
    void shouldRestoreTheFrozenContractAndRejectAChangedSandboxPolicy() {
        Fixture fixture = new Fixture("solo");
        fixture.agent.setStatus("active");
        fixture.execution.setStatus("running");
        fixture.execution.setTemporaryChat(true);
        fixture.execution.setMemoryWriteEnabled(false);
        fixture.execution.setSandboxPolicySnapshot(Map.of(
                "allowedToolCodes", List.of("read"), AgentAcceptance.SNAPSHOT_KEY, Map.of("/ok", "boolean")));
        when(fixture.sandbox.toSnapshot(any())).thenReturn(Map.of("allowedToolCodes", List.of("read")));

        fixture.runtime.recover("exec");

        var recovered = ArgumentCaptor.forClass(AgentExecutionContext.class);
        verify(fixture.strategy).recover(recovered.capture());
        assertThat(recovered.getValue().getAcceptance()).isEqualTo(new AgentAcceptance(Map.of("/ok", "boolean")));
        assertThat(recovered.getValue().isTemporaryChat()).isTrue();
        assertThat(recovered.getValue().getMemoryWriteEnabled()).isFalse();

        when(fixture.sandbox.toSnapshot(any())).thenReturn(Map.of("allowedToolCodes", List.of("read", "write")));
        assertThatThrownBy(() -> fixture.runtime.recover("exec"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("沙箱策略已变更");
    }

    private static final class Fixture {
        private final AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        private final SandboxPolicyResolver sandbox = mock(SandboxPolicyResolver.class);
        private final AgentConfigHandler configs = mock(AgentConfigHandler.class);
        private final AgentRuntimeStrategy strategy = mock(AgentRuntimeStrategy.class);
        private final Agent agent = new Agent();
        private final AgentExecution execution = new AgentExecution();
        private final AgentRuntimeOrchestrator runtime;

        private Fixture(String agentType) {
            AgentMapper agents = mock(AgentMapper.class);
            AgentRuntimeStrategyFactory strategies = mock(AgentRuntimeStrategyFactory.class);
            agent.setId("agent");
            agent.setTenantId("tenant");
            agent.setAgentType(agentType);
            execution.setId("exec");
            execution.setAgentId("agent");
            execution.setTenantId("tenant");
            execution.setConversationId("conv");
            when(agents.selectById("agent")).thenReturn(agent);
            when(executions.selectById("exec")).thenReturn(execution);
            when(strategies.getStrategy(agentType)).thenReturn(strategy);
            when(configs.loadConfigs("agent")).thenReturn(Collections.emptyList());
            when(sandbox.resolve(eq(agent), any())).thenReturn(SandboxPolicy.builder().build());
            when(sandbox.toSnapshot(any())).thenReturn(Collections.emptyMap());
            runtime = new AgentRuntimeOrchestrator(agents, executions, strategies, sandbox, configs);
        }

        private AgentExecutionContext resume(AgentExecutionInputDTO input) {
            runtime.resume("exec", input);
            ArgumentCaptor<AgentExecutionContext> context = ArgumentCaptor.forClass(AgentExecutionContext.class);
            verify(strategy).resume(eq(agent), eq(execution), context.capture(), eq(input));
            return context.getValue();
        }
    }
}
