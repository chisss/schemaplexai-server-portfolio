package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.model.ToolResult;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentExecutionEngineTest {

    @Test
    void shouldForceCompletionWhenEmptyResponseHasEvidence() {
        AgentExecutionEngine engine = new AgentExecutionEngine(
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                null
        );
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请输出回归摘要"),
                AiMessage.from("已收集到一部分事实"),
                ToolExecutionResultMessage.from("call-1", "sys.read", "{\"output\":\"ok\"}")
        ));

        assertThat(engine.shouldForceCompletionForEmptyResponse("", chatMemory)).isTrue();
    }

    @Test
    void shouldNotForceCompletionWhenEmptyResponseHasNoEvidence() {
        AgentExecutionEngine engine = new AgentExecutionEngine(
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                null
        );
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(UserMessage.from("请输出回归摘要")));

        assertThat(engine.shouldForceCompletionForEmptyResponse("", chatMemory)).isFalse();
    }

    @Test
    void shouldPreferBlockedToolResultWhenMultipleInterruptActionsExist() {
        AgentExecutionEngine engine = new AgentExecutionEngine(
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                null
        );

        ToolResult interrupted = engine.findInterruptedToolResult(List.of(
                ToolResult.builder()
                        .toolCode("shell_command")
                        .success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_PAUSE)
                        .build(),
                ToolResult.builder()
                        .toolCode("browser_run_code")
                        .success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_BLOCK)
                        .build()
        ));

        assertThat(interrupted).isNotNull();
        assertThat(interrupted.getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(interrupted.getToolCode()).isEqualTo("browser_run_code");
    }

    @Test
    void shouldPauseTeamRuntimeWhenToolRequiresHumanReview() {
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService executionEventStreamService = mock(ExecutionEventStreamService.class);
        AgentExecutionEngine engine = new AgentExecutionEngine(
                agentLogService,
                null,
                null,
                null,
                new ObjectMapper(),
                executionEventStreamService,
                null,
                null,
                null
        );

        AgentExecutionContext context = AgentExecutionContext.builder()
                .runtimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode())
                .build();
        ToolResult pausedTool = ToolResult.builder()
                .toolCode("shell_command")
                .success(false)
                .errorMessage("命中高危工具，等待人工复核")
                .controlAction(SecurityComplianceConstant.DECISION_PAUSE)
                .build();

        AgentExecutionResult result = engine.resolveInterruptedToolResult(
                context,
                "exec-1",
                "agent-1",
                "tenant-1",
                1,
                List.of(pausedTool),
                10L,
                20L,
                "conv-1",
                100L
        );

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.PAUSED.getCode());
        assertThat(result.getErrorMessage()).contains("等待人工复核");
        verify(agentLogService).updateExecutionStatus("exec-1", AgentExecutionStatusEnum.PAUSED.getCode(),
                "命中高危工具，等待人工复核", 10L, 20L, null);
        verify(executionEventStreamService).publish(org.mockito.ArgumentMatchers.any());
    }
}
