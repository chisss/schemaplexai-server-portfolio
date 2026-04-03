package com.schemaplexai.service.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultToolRegistryTest {

    @Test
    void shouldPassSandboxPolicyToToolExecutor() {
        AgentToolBindingMapper agentToolBindingMapper = mock(AgentToolBindingMapper.class);
        BuiltinToolMapper builtinToolMapper = mock(BuiltinToolMapper.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);
        SandboxGuard sandboxGuard = mock(SandboxGuard.class);
        when(securityRuntimeGuardService.evaluate(
                ArgumentMatchers.any(SecurityRuntimeCheckRequest.class),
                ArgumentMatchers.isNull()))
                .thenReturn((SecurityCheckDecisionVO) null);
        when(sandboxGuard.validateTool(
                ArgumentMatchers.any(SandboxPolicy.class),
                ArgumentMatchers.any(AgentToolBinding.class),
                ArgumentMatchers.any(ToolCall.class)))
                .thenReturn(null);

        RecordingToolExecutor executor = new RecordingToolExecutor();
        DefaultToolRegistry registry = new DefaultToolRegistry(
                new ObjectMapper(),
                agentToolBindingMapper,
                builtinToolMapper,
                List.of(executor),
                securityRuntimeGuardService,
                sandboxGuard
        );

        AgentToolBinding binding = new AgentToolBinding();
        binding.setAgentId("agent-1");
        binding.setTenantId("tenant-1");
        binding.setToolCode("sys.read");
        binding.setSourceType("builtin");
        binding.setEnabled(true);
        binding.setPriority(10);

        ToolCall toolCall = ToolCall.builder()
                .callId("call-1")
                .toolCode("sys.read")
                .arguments(new ObjectMapper().createObjectNode().put("path", "docs/30-Team-Agent-LangGraph4J-执行环境优化方案.md"))
                .build();

        SandboxPolicy sandboxPolicy = SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .build();

        List<ToolResult> results = registry.executeAll(
                "tenant-1",
                "agent-1",
                List.of(toolCall),
                List.of(binding),
                sandboxPolicy
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().isSuccess()).isTrue();
        assertThat(executor.lastSandboxPolicy).isSameAs(sandboxPolicy);
    }

    @Test
    void shouldPreservePauseControlActionWhenSecurityReviewIsRequired() {
        AgentToolBindingMapper agentToolBindingMapper = mock(AgentToolBindingMapper.class);
        BuiltinToolMapper builtinToolMapper = mock(BuiltinToolMapper.class);
        SecurityRuntimeGuardService securityRuntimeGuardService = mock(SecurityRuntimeGuardService.class);
        SandboxGuard sandboxGuard = mock(SandboxGuard.class);
        when(sandboxGuard.validateTool(
                ArgumentMatchers.any(SandboxPolicy.class),
                ArgumentMatchers.any(AgentToolBinding.class),
                ArgumentMatchers.any(ToolCall.class)))
                .thenReturn(null);
        SecurityCheckDecisionVO pauseDecision = new SecurityCheckDecisionVO();
        pauseDecision.setDecision(SecurityComplianceConstant.DECISION_PAUSE);
        pauseDecision.setMessage("命中高危工具，等待人工复核");
        when(securityRuntimeGuardService.evaluate(
                ArgumentMatchers.any(SecurityRuntimeCheckRequest.class),
                ArgumentMatchers.isNull()))
                .thenReturn(pauseDecision);

        DefaultToolRegistry registry = new DefaultToolRegistry(
                new ObjectMapper(),
                agentToolBindingMapper,
                builtinToolMapper,
                List.of(new RecordingToolExecutor()),
                securityRuntimeGuardService,
                sandboxGuard
        );

        AgentToolBinding binding = new AgentToolBinding();
        binding.setAgentId("agent-1");
        binding.setTenantId("tenant-1");
        binding.setToolCode("shell_command");
        binding.setSourceType("builtin");
        binding.setEnabled(true);
        binding.setPriority(10);

        ToolCall toolCall = ToolCall.builder()
                .callId("call-1")
                .toolCode("shell_command")
                .arguments(new ObjectMapper().createObjectNode().put("command", "ls"))
                .build();

        List<ToolResult> results = registry.executeAll(
                "tenant-1",
                "agent-1",
                List.of(toolCall),
                List.of(binding),
                SandboxPolicy.builder().tenantId("tenant-1").agentId("agent-1").executionId("exec-1").build()
        );

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().isSuccess()).isFalse();
        assertThat(results.getFirst().getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
        assertThat(results.getFirst().getErrorMessage()).contains("等待人工复核");
    }

    private static final class RecordingToolExecutor implements ToolExecutor {

        private SandboxPolicy lastSandboxPolicy;

        @Override
        public String sourceType() {
            return "builtin";
        }

        @Override
        public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
            return ToolResult.builder()
                    .callId(toolCall.getCallId())
                    .toolCode(toolCall.getToolCode())
                    .success(true)
                    .build();
        }

        @Override
        public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall, SandboxPolicy sandboxPolicy) {
            this.lastSandboxPolicy = sandboxPolicy;
            return execute(tenantId, agentId, binding, toolCall);
        }
    }
}
