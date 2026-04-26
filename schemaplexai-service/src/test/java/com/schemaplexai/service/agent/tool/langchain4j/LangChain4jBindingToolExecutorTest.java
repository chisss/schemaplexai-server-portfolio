package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangChain4jBindingToolExecutorTest {

    @Test
    void shouldExecuteUsingActualBindingToolCodeWhenModelNameIsNormalized() {
        ToolExecutor delegate = mock(ToolExecutor.class);
        AgentToolBinding binding = new AgentToolBinding();
        binding.setToolCode("sys.read");
        SandboxPolicy sandboxPolicy = SandboxPolicy.builder().build();
        when(delegate.execute(eq("tenant-1"), eq("agent-1"), eq(binding), org.mockito.ArgumentMatchers.any(ToolCall.class), eq(sandboxPolicy)))
                .thenReturn(ToolResult.builder()
                        .toolCode("sys.read")
                        .success(true)
                        .build());

        LangChain4jBindingToolExecutor executor = new LangChain4jBindingToolExecutor(
                new ObjectMapper(),
                delegate,
                "tenant-1",
                "agent-1",
                binding,
                sandboxPolicy
        );

        executor.executeWithContext(ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys_read")
                .arguments("{\"path\":\"README.md\"}")
                .build(), null);

        ArgumentCaptor<ToolCall> toolCallCaptor = ArgumentCaptor.forClass(ToolCall.class);
        verify(delegate).execute(eq("tenant-1"), eq("agent-1"), eq(binding), toolCallCaptor.capture(), eq(sandboxPolicy));
        assertThat(toolCallCaptor.getValue().getToolCode()).isEqualTo("sys.read");
    }
}
