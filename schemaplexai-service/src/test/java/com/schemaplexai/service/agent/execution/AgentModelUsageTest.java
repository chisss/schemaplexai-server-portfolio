package com.schemaplexai.service.agent.execution;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.schemaplexai.model.vo.monitor.ModelAttemptMetadata;
import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentModelUsageTest {
    @Test
    void recordsOneTracePerBudgetedInferenceOrAuxiliaryAttempt() throws Exception {
        AgentTraceService traces = mock(AgentTraceService.class);
        AgentModelInvoker invoker = new AgentModelInvoker(mock(AgentHookExecutor.class),
                mock(ModelMetricsTracker.class), mock(RouteAnalysisService.class), traces,
                mock(LangChain4jModelFactory.class));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(" "))
                        .id("provider-empty").tokenUsage(new TokenUsage(8, 1)).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("摘要"))
                        .id("provider-summary").tokenUsage(new TokenUsage(13, 5)).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("结果"))
                        .tokenUsage(new TokenUsage(21)).build());
        var resolution = new LangChain4jResolution(model, null);
        var request = ChatRequest.builder().messages(UserMessage.from("验证模型用量")).build();
        var params = AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build();
        var logs = mock(AgentLogService.class);
        long start = System.currentTimeMillis();
        var state = new AgentLoopState();
        var budget = new AgentExecutionBudget(start + 10_000, 5_000, 0, start, state, () -> false);

        assertThatThrownBy(() -> invoker.invokeWithRetry(resolution, request, params,
                "execution", "agent", "tenant", 1, start, logs, budget, "compaction"))
                .isInstanceOf(AgentModelInvoker.EmptyResponseException.class);
        invoker.invokeWithRetry(resolution, request, params, "execution", "agent", "tenant", 1, start,
                logs, budget, "compaction");
        invoker.invokeChainWithRetry(List.of(resolution), request, params, "execution", "agent", "tenant", 1, start,
                logs, budget);

        var inputs = ArgumentCaptor.forClass(Long.class);
        var outputs = ArgumentCaptor.forClass(Long.class);
        var attemptIds = ArgumentCaptor.forClass(String.class);
        var metadata = ArgumentCaptor.forClass(ModelAttemptMetadata.class);
        LocalDateTime now = LocalDateTime.now();
        var dispatchTimes = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(traces).recordModelAttempt(eq("execution"), eq("tenant"), eq("agent"), attemptIds.capture(), any(),
                dispatchTimes.capture(), anyLong(), eq(8L), eq(1L), isNull(), eq("FAILED"), metadata.capture());
        verify(traces, times(2)).recordModelAttempt(eq("execution"), eq("tenant"), eq("agent"), attemptIds.capture(), any(),
                dispatchTimes.capture(), anyLong(), inputs.capture(), outputs.capture(), isNull(), eq("SUCCESS"), metadata.capture());
        assertThat(attemptIds.getAllValues()).map(UUID::fromString).doesNotHaveDuplicates();
        assertThat(dispatchTimes.getAllValues()).allSatisfy(time -> assertThat(time).isBeforeOrEqualTo(now));
        assertThat(metadata.getAllValues()).containsExactly(
                new ModelAttemptMetadata(1, "compaction", 1, "provider-empty", false, false, null, null, null),
                new ModelAttemptMetadata(1, "compaction", 1, "provider-summary", false, false, null, null, null),
                new ModelAttemptMetadata(1, "inference", 1, null, false, true, null, null, null));
        assertThat(inputs.getAllValues()).containsExactly(13L, 21L);
        assertThat(state.getTotalTokenInput()).isEqualTo(42L);
        assertThat(state.getTotalTokenOutput()).isEqualTo(1 + outputs.getAllValues().stream().mapToLong(Long::longValue).sum());
        assertThat(outputs.getAllValues().getLast()).isPositive();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metersStreamingRejectionBeforeDecidingWhetherSynchronousFallbackFits(boolean fallbackFits) throws Exception {
        var traces = mock(AgentTraceService.class);
        var factory = mock(LangChain4jModelFactory.class);
        var streaming = mock(StreamingChatModel.class);
        var synchronous = mock(ChatModel.class);
        var config = AiModelConfig.builder().provider("openai").modelId("metered-stream-rejection")
                .maxTokens(128).contextWindowTokens(8_000).build();
        var request = ChatRequest.builder().messages(UserMessage.from("预算检查".repeat(100))).build();
        var planner = new AgentModelRequestPlanner(new TokenEstimatorSupport());
        long estimatedInput = planner.estimateInput(config, request.messages(), List.of());
        when(factory.getOrCreateStreaming(config)).thenReturn(streaming);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            var rejection = fallbackFits
                    ? new InternalServerException(new HttpException(501, "Not implemented"))
                    : new InvalidRequestException(new HttpException(400, "stream is not supported"));
            handler.onError(rejection);
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(synchronous.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("回退结果")).id("fallback-response")
                .tokenUsage(new TokenUsage(79, 3)).build());
        var invoker = new AgentModelInvoker(mock(AgentHookExecutor.class), mock(ModelMetricsTracker.class),
                mock(RouteAnalysisService.class), traces, factory);
        var state = new AgentLoopState();
        long start = System.currentTimeMillis();
        var budget = new AgentExecutionBudget(start + 10_000, estimatedInput + (fallbackFits ? 5_000 : 256),
                0, start, state, () -> false);
        var models = List.of(new LangChain4jResolution(synchronous, config));
        var params = AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build();
        var logs = mock(AgentLogService.class);

        var firstId = ArgumentCaptor.forClass(String.class);
        verifyNoInteractions(traces);
        if (fallbackFits) {
            var answer = invoker.invokeChainWithRetry(models, request, params, "execution", "agent", "tenant",
                    1, start, logs, budget, progress -> {});
            assertThat(answer.response().aiMessage().text()).isEqualTo("回退结果");
            verify(synchronous).chat(any(ChatRequest.class));
        } else {
            assertThatThrownBy(() -> invoker.invokeChainWithRetry(models, request, params, "execution", "agent", "tenant",
                    1, start, logs, budget, progress -> {}))
                    .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class);
            verifyNoInteractions(synchronous);
        }
        verify(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(traces).recordModelAttempt(eq("execution"), eq("tenant"), eq("agent"), firstId.capture(), any(), any(),
                anyLong(), eq(estimatedInput), eq(0L), isNull(), eq("FAILED"),
                eq(new ModelAttemptMetadata(1, "inference", 1, null, true, true, null, null, null)));
        verify(traces, times(fallbackFits ? 1 : 0)).recordModelAttempt(eq("execution"), eq("tenant"), eq("agent"),
                any(), any(), any(), anyLong(), eq(79L), eq(3L), isNull(), eq("SUCCESS"),
                eq(new ModelAttemptMetadata(1, "inference", 2, "fallback-response", false, false, null, null, null)));
        assertThat(state.getTotalTokenInput()).isEqualTo(estimatedInput + (fallbackFits ? 79 : 0));
        assertThat(state.getTotalTokenOutput()).isEqualTo(fallbackFits ? 3 : 0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void logsOnlyFittedRequestEstimatesWhenEnabled(boolean enabled) throws Exception {
        AgentTraceService traces = mock(AgentTraceService.class);
        AgentModelInvoker invoker = new AgentModelInvoker(mock(AgentHookExecutor.class),
                mock(ModelMetricsTracker.class), mock(RouteAnalysisService.class), traces,
                mock(LangChain4jModelFactory.class));
        if (enabled) {
            ReflectionTestUtils.setField(invoker, "logRequestComponents", true);
        }
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("模型输出夹具"))
                .id("provider-fixture").tokenUsage(new TokenUsage(71, 5)).build());
        var config = AiModelConfig.builder().provider("openai").modelId("custom-fixture")
                .maxTokens(700).contextWindowTokens(500).build();
        var system = SystemMessage.from("系统消息夹具");
        var user = UserMessage.from("用户消息夹具");
        var tool = ToolExecutionRequest.builder().id("tool-call").name("lookup").arguments("{}").build();
        var assistant = AiMessage.builder().text("助手文本夹具").toolExecutionRequests(List.of(tool)).build();
        var toolResult = ToolExecutionResultMessage.from(tool, "工具结果夹具");
        var toolSpec = ToolSpecification.builder().name("lookup").description("工具定义夹具").build();
        var request = ChatRequest.builder().messages(system, UserMessage.from("旧对话夹具".repeat(120)),
                user, assistant, toolResult).toolSpecifications(toolSpec).build();
        var params = AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build();
        long start = System.currentTimeMillis();
        var state = new AgentLoopState();
        var budget = new AgentExecutionBudget(start + 10_000, 5_000, 0, start, state, () -> false);
        var appender = new ListAppender<ILoggingEvent>();
        var logger = (Logger) LoggerFactory.getLogger(AgentModelInvoker.class);
        appender.start();
        logger.addAppender(appender);
        try {
            invoker.invokeWithRetry(new LangChain4jResolution(model, config), request, params,
                    "execution", "agent", "tenant", 1, start, mock(AgentLogService.class), budget, "inference");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        var dispatch = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(dispatch.capture());
        assertThat(dispatch.getValue().messages()).containsExactly(system, user, assistant, toolResult);
        assertThat(dispatch.getValue().toolSpecifications()).containsExactly(toolSpec);
        var id = ArgumentCaptor.forClass(String.class);
        verify(traces).recordModelAttempt(eq("execution"), eq("tenant"), eq("agent"), id.capture(), any(),
                any(), anyLong(), eq(71L), eq(5L), isNull(), eq("SUCCESS"), any());
        assertThat(state.getTotalTokenInput()).isEqualTo(71);
        var logged = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("agent_model_request_components ")).toList();
        var payloads = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("agent_model_request_tool_payloads ")).toList();
        if (!enabled) {
            assertThat(logged).isEmpty();
            assertThat(payloads).isEmpty();
            return;
        }
        var estimator = new TokenEstimatorSupport();
        var planner = new AgentModelRequestPlanner(estimator);
        long framing = estimator.estimateMessages(config, List.of());
        assertThat(logged).containsExactly(String.format(
                "agent_model_request_components attemptId=%s estimatedInput=%d system=%d user=%d toolResults=%d toolSchemas=%d other=%d framing=%d",
                id.getValue(), planner.estimateInput(config, dispatch.getValue().messages(), List.of(toolSpec)),
                estimator.estimateMessage(config, system), estimator.estimateMessage(config, user),
                estimator.estimateMessage(config, toolResult), planner.estimateInput(config, List.of(), List.of(toolSpec)) - framing,
                estimator.estimateMessage(config, assistant), framing));
        assertThat(payloads).containsExactly(String.format(
                "agent_model_request_tool_payloads attemptId=%s assistantText=%d resultTokensByTool={other=%d} argumentTokensByTool={other=%d}",
                id.getValue(), estimator.estimateText(config, assistant.text()), estimator.estimateMessage(config, toolResult),
                estimator.estimateText(config, tool.arguments())));
    }
}
