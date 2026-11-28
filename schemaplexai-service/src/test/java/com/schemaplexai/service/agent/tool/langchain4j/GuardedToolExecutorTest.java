package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import org.mockito.ArgumentCaptor;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardedToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void legacyExecutePreservesTenantExecutionAndMemoryIds() {
        var delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        when(delegate.executeWithContext(any(), any()))
                .thenReturn(ToolExecutionResult.builder().resultText("ok").build());
        var executor = new GuardedToolExecutor(delegate, objectMapper, "tenant-1", "agent-1",
                null, "builtin", policy(), new SandboxGuard(), null, "execution-1");

        assertThat(executor.execute(request("sys.read"), "conversation-1")).isEqualTo("ok");
        var context = ArgumentCaptor.forClass(InvocationContext.class);
        verify(delegate).executeWithContext(any(), context.capture());
        assertThat(context.getValue().chatMemoryId()).isEqualTo("conversation-1");
        assertThat(context.getValue().invocationParameters().asMap())
                .containsEntry("tenantId", "tenant-1").containsEntry("agentId", "agent-1")
                .containsEntry("executionId", "execution-1");
    }

    @Test
    void shouldRejectExecutionWhenSandboxPolicyMissing() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin", null, new SandboxGuard(), null);

        ToolExecutionResult result = executor.executeWithContext(request("sys.bash"), null);

        assertThat(result.isError()).isTrue();
        assertThat(result.result()).isInstanceOf(ToolResult.class);
        ToolResult platform = (ToolResult) result.result();
        assertThat(platform.isSuccess()).isFalse();
        assertThat(platform.getErrorMessage()).contains("fail-closed");
        verify(delegate, never()).executeWithContext(any(), any());
    }

    @Test
    void shouldInvokeComplianceChecksAndPassThroughSuccessResult() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        ToolExecutionResult ok = ToolExecutionResult.builder()
                .isError(false)
                .result(ToolResult.builder().callId("call-1").toolCode("sys.read").success(true).build())
                .resultText("{}")
                .attributes(Map.of("activatedSkillName", "skill.excel"))
                .build();
        when(delegate.executeWithContext(any(), any())).thenReturn(ok);
        SecurityRuntimeGuardService guardService = mock(SecurityRuntimeGuardService.class);
        when(guardService.evaluate(any(), isNull())).thenReturn(null);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin", policy(), new SandboxGuard(), guardService);

        ToolExecutionResult result = executor.executeWithContext(request("sys.read"), null);

        assertThat(result).isSameAs(ok);
        assertThat(result.attributes()).containsEntry("activatedSkillName", "skill.excel");
        verify(guardService, times(2)).evaluate(any(), isNull());
        verify(delegate, times(1)).executeWithContext(any(), any());
    }

    @Test
    void shouldBlockBeforeDelegateWhenPreDecisionRequiresInterrupt() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        SecurityRuntimeGuardService guardService = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO block = new SecurityCheckDecisionVO();
        block.setDecision(SecurityComplianceConstant.DECISION_BLOCK);
        block.setMessage("命中高危工具规则");
        when(guardService.evaluate(any(), isNull())).thenReturn(block);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin", policy(), new SandboxGuard(), guardService);

        ToolExecutionResult result = executor.executeWithContext(request("shell_command"), null);

        assertThat(result.isError()).isTrue();
        ToolResult platform = (ToolResult) result.result();
        assertThat(platform.getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(platform.getErrorMessage()).contains("命中高危工具规则");
        verify(delegate, never()).executeWithContext(any(), any());
        verify(guardService, times(1)).evaluate(any(), isNull());
    }

    @Test
    void shouldBlockAfterDelegateWhenPostDecisionRequiresInterrupt() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        ToolExecutionResult ok = ToolExecutionResult.builder()
                .isError(false)
                .result(ToolResult.builder().callId("call-1").toolCode("sys.bash").success(true).build())
                .resultText("{}")
                .build();
        when(delegate.executeWithContext(any(), any())).thenReturn(ok);
        SecurityRuntimeGuardService guardService = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO pause = new SecurityCheckDecisionVO();
        pause.setDecision(SecurityComplianceConstant.DECISION_PAUSE);
        pause.setMessage("等待人工复核");
        when(guardService.evaluate(any(), isNull())).thenReturn(null, pause);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin", policy(), new SandboxGuard(), guardService);

        ToolExecutionResult result = executor.executeWithContext(request("sys.bash"), null);

        assertThat(result.isError()).isTrue();
        ToolResult platform = (ToolResult) result.result();
        assertThat(platform.getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
        assertThat(platform.getErrorMessage()).contains("等待人工复核");
        verify(delegate, times(1)).executeWithContext(any(), any());
        verify(guardService, times(2)).evaluate(any(), isNull());
    }

    @Test
    void shouldFailOpenWhenEscapeValveEnabled() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        ToolExecutionResult ok = ToolExecutionResult.builder()
                .isError(false)
                .result(ToolResult.builder().callId("call-1").toolCode("sys.read").success(true).build())
                .resultText("{}")
                .build();
        when(delegate.executeWithContext(any(), any())).thenReturn(ok);
        SandboxGuard guard = new SandboxGuard();
        ReflectionTestUtils.setField(guard, "failOpenOnNull", true);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin", null, guard, null);

        ToolExecutionResult result = executor.executeWithContext(request("sys.read"), null);

        assertThat(result).isSameAs(ok);
        verify(delegate, times(1)).executeWithContext(any(), any());
    }

    @Test
    void shouldRejectMalformedJsonBeforeDelegate() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        SecurityRuntimeGuardService guardService = mock(SecurityRuntimeGuardService.class);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin",
                policy(), new SandboxGuard(), guardService);
        ToolExecutionRequest malformed = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments("{\"path\":")
                .build();

        ToolExecutionResult result = executor.executeWithContext(malformed, null);

        assertThat(result.isError()).isTrue();
        assertThat(((ToolResult) result.result()).getErrorMessage()).contains("JSON 非法");
        verify(delegate, never()).executeWithContext(any(), any());
        verify(guardService, never()).evaluate(any(), any());
    }

    @Test
    void shouldPassTheSameCanonicalArgumentsToGuardAndDelegate() {
        dev.langchain4j.service.tool.ToolExecutor delegate = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        when(delegate.executeWithContext(any(), any()))
                .thenReturn(ToolExecutionResult.builder().resultText("ok").build());
        SecurityRuntimeGuardService guardService = mock(SecurityRuntimeGuardService.class);
        GuardedToolExecutor executor = new GuardedToolExecutor(
                delegate, objectMapper, "tenant-1", "agent-1", null, "builtin",
                policy(), new SandboxGuard(), guardService);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments(" { \"path\" : \"README.md\" } ")
                .build();

        executor.executeWithContext(request, null);

        ArgumentCaptor<ToolExecutionRequest> delegated = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(delegate).executeWithContext(delegated.capture(), any());
        assertThat(delegated.getValue().arguments()).isEqualTo("{\"path\":\"README.md\"}");
        ArgumentCaptor<com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest> checked =
                ArgumentCaptor.forClass(com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest.class);
        verify(guardService, times(2)).evaluate(checked.capture(), any());
        assertThat(checked.getAllValues()).allSatisfy(call ->
                assertThat(call.getArguments()).containsEntry("path", "README.md"));
    }

    private SandboxPolicy policy() {
        return SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .sandboxProfile(SandboxProfileEnum.STANDARD)
                .networkEgressEnabled(true)
                .build();
    }

    private ToolExecutionRequest request(String name) {
        return ToolExecutionRequest.builder()
                .id("call-1")
                .name(name)
                .arguments("{}")
                .build();
    }
}
