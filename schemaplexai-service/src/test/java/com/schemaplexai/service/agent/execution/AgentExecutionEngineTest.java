package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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
    void shouldPauseSoloRuntimeWhenToolRequiresHumanReview() {
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
                .runtimeEngine(AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J.getCode())
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

    @Test
    void shouldBuildResumeUserMessageWithOptions() throws Exception {
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
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("请继续完成剩余测试");
        input.setOptions(Map.of(
                "incidentId", "inc-1",
                "approved", true
        ));

        Method method = AgentExecutionEngine.class.getDeclaredMethod("buildResumeUserMessage", AgentExecutionInputDTO.class);
        method.setAccessible(true);
        String result = (String) method.invoke(engine, input);

        assertThat(result).contains("## 人工补充输入");
        assertThat(result).contains("请继续完成剩余测试");
        assertThat(result).contains("incidentId");
        assertThat(result).contains("inc-1");
        assertThat(result).contains("approved");
        assertThat(result).contains("不要重复已完成部分");
    }

    @Test
    void shouldGenerateQualityReflectionFeedbackWhenDetectorFindsIssue() throws Exception {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(null, "structural", "TODO: 待补充"))
                .thenReturn(new QualityDetector.DetectionResult(
                        true,
                        "warning",
                        "结构不完整",
                        Map.of("missingSection", "验证")
                ));
        AgentExecutionEngine engine = new AgentExecutionEngine(
                null,
                null,
                null,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                qualityOrchestrator
        );

        Method method = AgentExecutionEngine.class.getDeclaredMethod("buildQualityReflectionFeedback", String.class, int.class);
        method.setAccessible(true);
        Object feedback = method.invoke(engine, "TODO: 待补充", 0);

        assertThat(feedback).isNotNull();
        Method messageMethod = feedback.getClass().getDeclaredMethod("message");
        messageMethod.setAccessible(true);
        Method promptMethod = feedback.getClass().getDeclaredMethod("prompt");
        promptMethod.setAccessible(true);

        assertThat((String) messageMethod.invoke(feedback)).contains("结构不完整");
        assertThat((String) promptMethod.invoke(feedback)).contains("质量检测发现当前输出仍有问题");
        assertThat((String) promptMethod.invoke(feedback)).contains("missingSection");
        assertThat((String) promptMethod.invoke(feedback)).contains("验证");
    }
}
