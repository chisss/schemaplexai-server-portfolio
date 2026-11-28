package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.PendingToolApprovalMapper;
import com.schemaplexai.dao.mapper.ToolApprovalBatchMapper;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.model.entity.PendingToolApproval;
import com.schemaplexai.model.entity.ToolApprovalBatch;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.agent.execution.approval.CreatePendingToolApprovalCommand;
import com.schemaplexai.service.agent.execution.approval.PendingToolApprovalService;
import com.schemaplexai.service.agent.execution.approval.ToolApprovalBatchService;
import com.schemaplexai.service.agent.execution.steering.ExecutionSteeringService;
import com.schemaplexai.service.memory.ArchivedChatMemory;
import com.schemaplexai.service.agent.execution.policy.ExecutionModePolicyResolver;
import com.schemaplexai.service.agent.execution.policy.ExecutionModePromptContributor;
import com.schemaplexai.service.agent.execution.policy.ToolExecutionGate;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.ai.MultimodalMessageBuilder;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolServiceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AgentExecutionEngineTest {

    // =========================================================================

    @Test
    void shouldReceiveStreamingProgressAndKeepTheCompletedModelResponse() throws Exception {
        var factory = mock(LangChain4jModelFactory.class);
        var streaming = mock(StreamingChatModel.class);
        var synchronous = mock(ChatModel.class);
        var config = AiModelConfig.builder().modelId("activity-stream").build();
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("检查文档"))).build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from("检查完成")).build();
        when(factory.getOrCreateStreaming(config)).thenReturn(streaming);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            var context = new PartialResponseContext(mock(StreamingHandle.class));
            handler.onPartialResponse(new PartialResponse("检查"), context);
            handler.onPartialResponse(new PartialResponse("完成"), context);
            handler.onCompleteResponse(response);
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        var progress = new ArrayList<AgentModelInvoker.ModelProgress>();
        var invoker = buildModelInvoker(factory);

        var result = invoker.invokeChainWithRetry(List.of(new LangChain4jResolution(synchronous, config)),
                request, AgentEngineParams.builder().modelCallTimeoutMillis(1000).build(),
                "execution", "agent", "tenant", 1, System.currentTimeMillis(), mock(AgentLogService.class), progress::add);

        assertThat(progress).containsExactly(AgentModelInvoker.ModelProgress.RESPONDING);
        assertThat(result.response()).isSameAs(response);
        var sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(streaming).chat(sent.capture(), any(StreamingChatResponseHandler.class));
        assertThat(sent.getValue().messages()).containsExactlyElementsOf(request.messages());
        verifyNoInteractions(synchronous);
    }

    @Test
    void shouldUseSynchronousModelWhenProviderExplicitlyRejectsStreaming() throws Exception {
        var factory = mock(LangChain4jModelFactory.class);
        var streaming = mock(StreamingChatModel.class);
        var synchronous = mock(ChatModel.class);
        var config = AiModelConfig.builder().modelId("activity-no-stream").build();
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("检查文档"))).build();
        var response = ChatResponse.builder().aiMessage(AiMessage.from("检查完成")).build();
        when(factory.getOrCreateStreaming(config)).thenReturn(streaming);
        doAnswer(call -> {
            StreamingChatResponseHandler handler = call.getArgument(1);
            handler.onError(new HttpException(400, "stream is not supported"));
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        when(synchronous.chat(any(ChatRequest.class))).thenReturn(response);
        var progress = new ArrayList<AgentModelInvoker.ModelProgress>();

        var result = buildModelInvoker(factory).invokeChainWithRetry(
                List.of(new LangChain4jResolution(synchronous, config)), request,
                AgentEngineParams.builder().modelCallTimeoutMillis(1000).build(),
                "execution", "agent", "tenant", 1, System.currentTimeMillis(), mock(AgentLogService.class), progress::add);

        assertThat(result.response()).isSameAs(response);
        assertThat(progress).isEmpty();
        var sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(synchronous).chat(sent.capture());
        assertThat(sent.getValue().messages()).containsExactlyElementsOf(request.messages());
    }
    //  AgentModelInvoker 测试
    // =========================================================================

    @Test
    void shouldTimeoutHungModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });
        AgentModelInvoker invoker = buildModelInvoker();

        assertThatThrownBy(() -> invoker.invokeWithTimeout(
                new LangChain4jResolution(chatModel, null),
                ChatRequest.builder().messages(List.of(UserMessage.from("请输出结果"))).toolSpecifications(List.of()).build(),
                50L
        ))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("AI 调用超时");
    }

    @Test
    void shouldUseConfiguredModelTimeoutWhenAvailable() {
        AgentModelInvoker invoker = buildModelInvoker();
        AiModelConfig config = AiModelConfig.builder().timeoutSeconds(180).build();

        long timeoutMillis = invoker.resolveTimeoutMillis(new LangChain4jResolution(null, config), 60_000L);

        assertThat(timeoutMillis).isEqualTo(180_000L);
    }

    @Test
    void shouldUseModelSpecificTimeoutDuringRetry() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(120L);
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });
        AgentModelInvoker invoker = buildModelInvoker();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(50L)
                .maxModelRetries(0)
                .build();
        LangChain4jResolution resolution = new LangChain4jResolution(
                chatModel,
                AiModelConfig.builder().provider("anthropic").modelId("slow-model").timeoutSeconds(1).build()
        );

        ChatResponse response = invoker.invokeWithRetry(
                resolution,
                ChatRequest.builder().messages(List.of(UserMessage.from("请输出结果"))).toolSpecifications(List.of()).build(),
                params,
                "exec-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                mock(AgentLogService.class)
        );

        assertThat(response.aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void shouldRetryCandidateOnNextInvocationAfterInvalidRequest() throws Exception {
        ChatModel requestSensitiveModel = mock(ChatModel.class);
        when(requestSensitiveModel.chat(any(ChatRequest.class)))
                .thenThrow(new InvalidRequestException("{\"error\":{\"message\":\"暂不支持此请求\",\"type\":\"invalid_request_error\"}}"))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("recovered")).build());
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = buildModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(200L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution requestSensitiveResolution = new LangChain4jResolution(
                requestSensitiveModel,
                AiModelConfig.builder().provider("anthropic").modelId("request-sensitive-model").timeoutSeconds(1).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(1).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(requestSensitiveResolution, healthyResolution),
                request,
                params,
                "exec-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(requestSensitiveResolution, healthyResolution),
                request,
                params,
                "exec-2", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("recovered");
        verify(requestSensitiveModel, times(2)).chat(any(ChatRequest.class));
        verify(healthyModel).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldScopeAuthenticationCooldownToTenantAndModelConfiguration() throws Exception {
        AgentModelInvoker invoker = buildModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        ChatModel failingModel = mock(ChatModel.class);
        when(failingModel.chat(any(ChatRequest.class))).thenThrow(new AuthenticationException("credentials rejected"));
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());
        AiModelConfig failedConfig = AiModelConfig.builder().provider("openai").modelId("shared-model")
                .configId("model-config-a").baseUrl("https://test-provider.invalid/v1").apiKey("initial-fixture").build();
        LangChain4jResolution failing = new LangChain4jResolution(failingModel, failedConfig);
        LangChain4jResolution sameConfigOtherTenant = new LangChain4jResolution(healthyModel, failedConfig);
        LangChain4jResolution anotherConfig = new LangChain4jResolution(healthyModel,
                failedConfig.toBuilder().configId("model-config-b").build());
        LangChain4jResolution rotatedCredentials = new LangChain4jResolution(healthyModel,
                failedConfig.toBuilder().apiKey("rotated-fixture").build());
        ChatRequest request = ChatRequest.builder().messages(List.of(UserMessage.from("请输出结果"))).build();
        AgentEngineParams params = AgentEngineParams.builder().modelCallTimeoutMillis(200L).maxModelRetries(0).build();
        AgentLogService logs = mock(AgentLogService.class);
        long start = System.currentTimeMillis();

        assertThatThrownBy(() -> invoker.invokeChainWithRetry(List.of(failing), request, params,
                "exec-auth-1", "agent", "tenant-a", 1, start, logs)).isInstanceOf(AuthenticationException.class);
        assertThat(invoker.invokeChainWithRetry(List.of(anotherConfig), request, params,
                "exec-auth-2", "agent", "tenant-a", 1, start, logs).response().aiMessage().text()).isEqualTo("healthy");
        assertThat(invoker.invokeChainWithRetry(List.of(sameConfigOtherTenant), request, params,
                "exec-auth-3", "agent", "tenant-b", 1, start, logs).response().aiMessage().text()).isEqualTo("healthy");
        assertThat(invoker.invokeChainWithRetry(List.of(rotatedCredentials), request, params,
                "exec-auth-4", "agent", "tenant-a", 1, start, logs).response().aiMessage().text()).isEqualTo("healthy");
        assertThatThrownBy(() -> invoker.invokeChainWithRetry(List.of(failing), request, params,
                "exec-auth-5", "agent", "tenant-a", 1, start, logs)).hasMessageContaining("冷却期");
        verify(failingModel).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldTreatChineseTimeoutAsRetryableCooldownAndSkipOnNextRound() throws Exception {
        ChatModel slowModel = mock(ChatModel.class);
        when(slowModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return ChatResponse.builder().aiMessage(AiMessage.from("slow")).build();
        });
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = buildModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(50L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution slowResolution = new LangChain4jResolution(
                slowModel,
                AiModelConfig.builder().provider("anthropic").modelId("slow-timeout-model").timeoutSeconds(0).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(0).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(slowResolution, healthyResolution),
                request,
                params,
                "exec-timeout-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(slowResolution, healthyResolution),
                request,
                params,
                "exec-timeout-2", "agent-1", "tenant-1", 2, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(invoker.isRetryable(new IllegalStateException("AI 调用超时，超过 1 秒"))).isTrue();
        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("healthy");
        verify(slowModel, times(1)).chat(any(ChatRequest.class));
        verify(healthyModel, times(2)).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldTreatProvider1302RateLimitAsRetryableCooldownAndSkipOnNextRound() throws Exception {
        ChatModel rateLimitedModel = mock(ChatModel.class);
        when(rateLimitedModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("{\"error\":{\"code\":\"1302\",\"message\":\"您的账户已达到速率限制，请您控制请求频率\"}}"));
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = buildModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(200L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution rateLimitedResolution = new LangChain4jResolution(
                rateLimitedModel,
                AiModelConfig.builder().provider("anthropic").modelId("rate-limit-model").timeoutSeconds(1).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(1).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(rateLimitedResolution, healthyResolution),
                request,
                params,
                "exec-rate-limit-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(rateLimitedResolution, healthyResolution),
                request,
                params,
                "exec-rate-limit-2", "agent-1", "tenant-1", 2, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(invoker.isRetryable(
                new IllegalStateException("{\"error\":{\"code\":\"1302\",\"message\":\"您的账户已达到速率限制，请您控制请求频率\"}}")
        )).isTrue();
        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("healthy");
        verify(rateLimitedModel, times(1)).chat(any(ChatRequest.class));
        verify(healthyModel, times(2)).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldFastFailNonFinalFallbackCandidateWithoutExtraRetries() throws Exception {
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AtomicInteger fallbackAttempts = new AtomicInteger();
        AtomicLong capturedTimeoutMillis = new AtomicLong();
        AgentModelInvoker invoker = new AgentModelInvoker(
                new AgentHookExecutor(List.of(), null),
                mock(ModelMetricsTracker.class),
                mock(RouteAnalysisService.class),
                mock(AgentTraceService.class),
                mock(LangChain4jModelFactory.class)
        ) {
            @Override
            public ChatResponse invokeWithTimeout(LangChain4jResolution resolution,
                                                  ChatRequest request,
                                                  long timeoutMillis) throws Exception {
                if ("slow-fallback".equals(resolution.config().getModelId())) {
                    fallbackAttempts.incrementAndGet();
                    capturedTimeoutMillis.set(timeoutMillis);
                    throw new IllegalStateException("timeout");
                }
                return super.invokeWithTimeout(resolution, request, timeoutMillis);
            }
        };

        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(TimeUnit.MINUTES.toMillis(2))
                .maxModelRetries(3)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();

        AgentModelInvoker.ModelCallResult result = invoker.invokeChainWithRetry(
                List.of(
                        new LangChain4jResolution(
                                mock(ChatModel.class),
                                AiModelConfig.builder()
                                        .provider("anthropic")
                                        .modelId("slow-fallback")
                                        .timeoutSeconds(120)
                                        .retryCount(5)
                                        .retryIntervalSeconds(2)
                                        .build()
                        ),
                        new LangChain4jResolution(
                                healthyModel,
                                AiModelConfig.builder()
                                        .provider("anthropic")
                                        .modelId("healthy-model")
                                        .timeoutSeconds(120)
                                        .retryCount(5)
                                        .retryIntervalSeconds(2)
                                        .build()
                        )
                ),
                request,
                params,
                "exec-fast-fail-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                mock(AgentLogService.class)
        );

        assertThat(result.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(fallbackAttempts.get()).isEqualTo(1);
        assertThat(capturedTimeoutMillis.get()).isEqualTo(TimeUnit.SECONDS.toMillis(30));
        verify(healthyModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldFallbackForBlankResponseAndReuseCandidateOnNextInvocation() throws Exception {
        ChatModel blankModel = mock(ChatModel.class);
        when(blankModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("")).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("recovered")).build());
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = buildModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(200L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution blankResolution = new LangChain4jResolution(
                blankModel,
                AiModelConfig.builder().provider("openai").modelId("gpt-5.4").timeoutSeconds(1).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("claude-sonnet-4-6").timeoutSeconds(1).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(blankResolution, healthyResolution),
                request,
                params,
                "exec-blank-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(blankResolution, healthyResolution),
                request,
                params,
                "exec-blank-2", "agent-1", "tenant-1", 2, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("recovered");
        verify(blankModel, times(2)).chat(any(ChatRequest.class));
        verify(healthyModel).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldAllowToolOnlyResponseWithoutBlankFallback() throws Exception {
        ChatModel toolModel = mock(ChatModel.class);
        when(toolModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("call-1").name("sys_read").arguments("{}").build()
                ))).build());

        AgentModelInvoker invoker = buildModelInvoker();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请读取文件")))
                .toolSpecifications(List.of())
                .build();

        AgentModelInvoker.ModelCallResult result = invoker.invokeChainWithRetry(
                List.of(new LangChain4jResolution(
                        toolModel,
                        AiModelConfig.builder().provider("openai").modelId("tool-model").timeoutSeconds(1).build()
                )),
                request,
                AgentEngineParams.builder().modelCallTimeoutMillis(200L).maxModelRetries(0).build(),
                "exec-tool-only", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                mock(AgentLogService.class)
        );

        assertThat(result.response().aiMessage().hasToolExecutionRequests()).isTrue();
        verify(toolModel, times(1)).chat(any(ChatRequest.class));
    }

    // =========================================================================
    //  AgentLoopToolHandler 测试
    // =========================================================================

    @Test
    void shouldDisableToolsDuringQualityReflectionRound() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder().name("sys.read").description("read file").build()
        );
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{}").build()
        );

        // qualityReflectionPending=true 时 effectiveTools 为空，filterExecutable 返回空
        List<ToolExecutionRequest> filteredWhenDisabled = handler.filterExecutable(requests, List.of());
        List<ToolExecutionRequest> filteredWhenEnabled  = handler.filterExecutable(requests, tools);

        assertThat(filteredWhenDisabled).isEmpty();
        assertThat(filteredWhenEnabled).hasSize(1);
    }

    @Test
    void shouldFilterOutToolRequestsWhenNoToolsAreAvailable() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{}").build()
        );
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder().name("sys.read").description("read file").build()
        );

        assertThat(handler.filterExecutable(requests, List.of())).isEmpty();
        assertThat(handler.filterExecutable(requests, tools)).hasSize(1);
        assertThat(handler.filterExecutable(requests, tools).getFirst().name()).isEqualTo("sys.read");
    }

    @Test
    void shouldTruncateOversizedToolResultMessage() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("sys.grep").arguments("{}").build();

        ToolExecutionResultMessage message = handler.buildResultMessage(
                request,
                ToolResult.builder().success(true).toolCode("sys.grep").build(),
                Map.of(),
                false,
                "x".repeat(15000)
        );

        assertThat(message.text()).hasSizeLessThan(13000);
        assertThat(message.text()).contains("工具输出过长，已截断");
    }

    @Test
    void shouldIncludeRequestPathInToolResultMessage() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-2").name("sys.read")
                .arguments("{\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\",\"workdir\":\"/tmp/workspace\"}")
                .build();

        ToolExecutionResultMessage message = handler.buildResultMessage(
                request,
                ToolResult.builder().success(true).toolCode("sys.read").build(),
                Map.of(),
                false,
                "{\"output\":\"class PolicyController {}\"}"
        );

        assertThat(message.text()).contains("请求参数");
        assertThat(message.text()).contains("PolicyController.java");
        assertThat(message.text()).contains("工具结果");
    }

    // =========================================================================
    //  AgentLoopCompletionHandler 测试
    // =========================================================================

    @Test
    void shouldForceCompletionWhenEmptyResponseHasEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请输出回归摘要"),
                AiMessage.from("已收集到一部分事实"),
                ToolExecutionResultMessage.from("call-1", "sys.read", "{\"output\":\"ok\"}")
        ));

        assertThat(handler.hasCompletionEvidence(chatMemory)).isTrue();
    }

    @Test
    void shouldNotForceCompletionWhenEmptyResponseHasNoEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(UserMessage.from("请输出回归摘要")));

        assertThat(handler.hasCompletionEvidence(chatMemory)).isFalse();
    }

    @Test
    void shouldPreferToolEvidenceOverAiConclusionsWhenCollectingFallbackEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请完成需求分析"),
                AiMessage.from("我推测 controller 应该在 com/schemaplexai 下"),
                ToolExecutionResultMessage.from(
                        "call-3", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"@RestController\"}"
                )
        ));
        AgentEngineParams params = AgentEngineParams.builder()
                .fallbackEvidenceLimit(5).fallbackTextLimit(200).build();

        List<String> evidences = handler.collectFallbackEvidence(chatMemory, params);

        assertThat(evidences).hasSize(1);
        assertThat(evidences.getFirst()).contains("PolicyController.java");
        assertThat(evidences.getFirst()).doesNotContain("我推测");
    }

    // =========================================================================
    //  AgentLoopQualityChecker 测试
    // =========================================================================

    @Test
    void shouldTellModelNotToCallToolsWhenQualityReflectionTriggered() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(true, "warning", "输出存在无效路径",
                        Map.of("invalidPaths", List.of("foo/bar.java"))));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildFeedback(null, "some content", null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.prompt()).contains("不要继续调用工具");
    }

    @Test
    void shouldGenerateQualityReflectionFeedbackWhenDetectorFindsIssue() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(null, "structural", "TODO: 待补充"))
                .thenReturn(new QualityDetector.DetectionResult(true, "warning", "结构不完整",
                        Map.of("missingSection", "验证")));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildFeedback(null, "TODO: 待补充", null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("结构不完整");
        assertThat(feedback.prompt()).contains("质量检测发现当前输出仍有问题");
        assertThat(feedback.prompt()).contains("missingSection");
        assertThat(feedback.prompt()).contains("验证");
    }

    @Test
    void shouldNotInvokeStructuralDetectorDuringImmediateFeedback() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildImmediateFeedback(null, "这是最终答案", null, 0, params);

        assertThat(feedback).isNull();
        verify(qualityOrchestrator, never()).executeDetection(any(), any(), any());
    }

    @Test
    void shouldGenerateDeliverableCompletenessFeedbackWhenOutputIsBlockedMessage() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        String output = """
                **无法完成此任务。**

                该文件内容未被成功读取，缺少必要输入，请人工补充输入后再继续。
                """;

        QualityReflectionFeedback feedback = checker.buildImmediateFeedback(null, output, null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("未形成可交付结果");
        assertThat(feedback.prompt()).contains("deliverable_completeness");
        assertThat(feedback.prompt()).contains("无法完成此任务");
        verify(qualityOrchestrator, never()).executeDetection(any(), any(), any());
    }

    @Test
    void shouldSanitizeInternalToolTracePlaceholderAndAbsolutePathFromFinalOutput() {
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(null);

        String sanitized = checker.sanitize("""
                # 文档
                - 证据来源: call_function_abcd1234_1
                - 检索方式: sys.read 和 sys.grep
                - 占位路径: .../infrastructure/repository/PolicyRepositoryImpl.java
                - 绝对路径: /Users/demo/workspace/src/main/java/com/example/PolicyController.java
                <minimax:tool_call>
                <invoke name="sys.read">
                <parameter name="path">titanium-policy-api/src/main/java/com/titanium/policy/api/PolicyChangePreviewApi.java</parameter>
                <parameter name="workdir">/Users/demo/.schemaplexai/workspaces/worktree</parameter>
                </invoke>
                </minimax:tool_call>
                """);

        assertThat(sanitized).doesNotContain("call_function_abcd1234_1");
        assertThat(sanitized).doesNotContain("sys.read");
        assertThat(sanitized).doesNotContain("sys.grep");
        assertThat(sanitized).doesNotContain(".../infrastructure/repository/PolicyRepositoryImpl.java");
        assertThat(sanitized).doesNotContain("/Users/demo/workspace/src/main/java/com/example/PolicyController.java");
        assertThat(sanitized).doesNotContain("<minimax:tool_call>");
        assertThat(sanitized).doesNotContain("<invoke");
        assertThat(sanitized).doesNotContain("/Users/demo/.schemaplexai/workspaces/worktree");
        assertThat(sanitized).contains("工具执行记录");
        assertThat(sanitized).contains("仓库检索工具");
        assertThat(sanitized).contains("仓库中未发现具体相对路径");
    }

    @Test
    void shouldGenerateGroundingFeedbackWhenMarkdownContainsMissingWorkspacePath(@TempDir Path workspaceRoot) throws Exception {
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller"));
        Files.writeString(
                workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java"),
                "class PolicyController {}"
        );
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .inputContext(Map.of("workspacePath", workspaceRoot.toString())).build();
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请输出需求分析"),
                ToolExecutionResultMessage.from("call-4", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"class PolicyController {}\"}")
        ));

        QualityReflectionFeedback feedback = checker.buildFeedback(ctx,
                "## 当前仓库已确认现状\n- titanium-policy-web/src/main/java/com/schemaplexai/policy/web/controller/PolicyController.java",
                chatMemory, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("不存在的文件路径");
        assertThat(feedback.prompt()).contains("com/schemaplexai");
        assertThat(feedback.prompt()).contains("invalidPaths");
    }

    @Test
    void shouldGenerateEvidenceCoverageFeedbackWhenToolHitsRealFilesButOutputDoesNotReferenceThem(@TempDir Path workspaceRoot) throws Exception {
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller"));
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-application/src/main/java/com/titanium/policy/application/command"));
        Files.createDirectories(workspaceRoot.resolve("liquibase"));
        Files.writeString(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java"), "class PolicyController {}");
        Files.writeString(workspaceRoot.resolve("titanium-policy-application/src/main/java/com/titanium/policy/application/command/PolicyApplicationService.java"), "class PolicyApplicationService {}");
        Files.writeString(workspaceRoot.resolve("liquibase/20260122_init_policy_tables.sql"), "-- ddl");

        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .inputPrompt("请输出最终 Markdown，并区分当前仓库已确认现状和建议改造/待实现项。")
                .inputContext(Map.of("workspacePath", workspaceRoot.toString())).build();
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请完成需求分析"),
                ToolExecutionResultMessage.from("call-1", "sys.grep",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"@RequestMapping(\\\"/api/policies\\\")\"}"),
                ToolExecutionResultMessage.from("call-2", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-application/src/main/java/com/titanium/policy/application/command/PolicyApplicationService.java\"}\n工具结果: {\"output\":\"class PolicyApplicationService {}\"}"),
                ToolExecutionResultMessage.from("call-3", "sys.read",
                        "请求参数: {\"path\":\"liquibase/20260122_init_policy_tables.sql\"}\n工具结果: {\"output\":\"create table t_policy (...)\"}")
        ));
        String output = """
                ## 当前仓库已确认现状
                - 已确认存在标准 Maven 模块结构，但当前尚未补充真实 controller、application 或 liquibase 文件路径。

                ## 建议改造/待实现项
                - 待确认后补充细节。
                """;

        QualityReflectionFeedback feedback = checker.buildFeedback(ctx, output, chatMemory, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("真实源码文件");
        assertThat(feedback.prompt()).contains("workspace_evidence_coverage");
        assertThat(feedback.prompt()).contains("PolicyController.java");
        assertThat(feedback.prompt()).contains("PolicyApplicationService.java");
        assertThat(feedback.prompt()).contains("20260122_init_policy_tables.sql");
    }

    @Test
    void shouldGenerateFormattingFeedbackWhenOutputContainsInternalToolTraceOrPlaceholderPath() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        String output = """
                # 需求分析
                - 证据来源: call_function_abcd1234_1
                - 检索方式: sys.read + sys.grep
                - 关键实现位于 .../infrastructure/repository/PolicyRepositoryImpl.java
                - 绝对路径示例: /Users/demo/workspace/src/main/java/com/example/PolicyController.java
                """;

        QualityReflectionFeedback feedback = checker.buildFeedback(null, output, null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("绝对路径");
        assertThat(feedback.prompt()).contains("artifact_formatting");
        assertThat(feedback.prompt()).contains("call_function_abcd1234_1");
        assertThat(feedback.prompt()).contains("sys.read");
        assertThat(feedback.prompt()).contains("sys.grep");
        assertThat(feedback.prompt()).contains(".../infrastructure/repository/PolicyRepositoryImpl.java");
        assertThat(feedback.prompt()).contains("/Users/demo/workspace/src/main/java/com/example/PolicyController.java");
    }

    // =========================================================================
    //  AgentExecutionEngine 测试（保留仍在 Engine 中的逻辑）
    // =========================================================================

    @Test
    void shouldSkipToolDispatchWhenStoppedDuringModelCall() throws Exception {
        ExecutionFixture fixture = executionFixture();
        CountDownLatch modelEntered = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("call-1").name("fixture_write").arguments("{}").build();
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            modelEntered.countDown();
            assertThat(releaseModel.await(5, TimeUnit.SECONDS)).isTrue();
            return ChatResponse.builder().aiMessage(AiMessage.from(List.of(toolRequest))).build();
        });

        CompletableFuture<AgentExecutionResult> task = CompletableFuture.supplyAsync(
                () -> fixture.engine().execute(fixture.context()).join());
        try {
            assertThat(modelEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(fixture.status().compareAndSet("running", "stopped")).isTrue();
        } finally {
            releaseModel.countDown();
        }

        assertThat(task.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("stopped");
        verifyNoInteractions(fixture.tool());
        verify(fixture.events()).publishActivity(eq("exec-1"), eq("agent-1"), eq("tenant-1"),
                argThat(activity -> activity.kind() == AgentExecutionActivity.Kind.ANALYSIS
                        && activity.status() == AgentExecutionActivity.Status.FAILED));
        verify(fixture.events(), never()).publishWithPayload(eq("exec-1"),
                eq(AgentExecutionEventTypeEnum.COMPLETED.getCode()), anyInt(), anyString(), any(), anyLong());
        verify(fixture.events(), never()).publishSimple(eq("exec-1"),
                eq(AgentExecutionEventTypeEnum.COMPLETED.getCode()), anyInt(), anyString(), anyLong());
    }

    @Test
    void shouldReturnPersistedStopWhenCancellationWinsCompletionTransition() {
        ExecutionFixture fixture = executionFixture();
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("已完成")).build());
        when(fixture.logs().tryFinalizeWithEvent(eq("exec-1"), eq("completed"),
                isNull(), anyLong(), anyLong(), eq("已完成"), isNull(), any())).thenAnswer(invocation -> {
            assertThat(fixture.status().compareAndSet("running", "stopped")).isTrue();
            return false;
        });

        assertThat(fixture.engine().execute(fixture.context()).join().getStatus()).isEqualTo("stopped");
        verify(fixture.logs()).tryFinalizeWithEvent(eq("exec-1"), eq("completed"),
                isNull(), longThat(count -> count > 0), longThat(count -> count > 0), eq("已完成"), isNull(), any());
        verify(fixture.events(), never()).publishWithPayload(eq("exec-1"),
                eq(AgentExecutionEventTypeEnum.COMPLETED.getCode()), anyInt(), anyString(), any(), anyLong());
    }

    @Test
    void shouldSkipStartingAnExecutionAlreadyStoppedInQueue() {
        ExecutionFixture fixture = executionFixture();
        fixture.status().set("stopped");

        assertThat(fixture.engine().execute(fixture.context()).join().getStatus()).isEqualTo("stopped");
        verifyNoInteractions(fixture.model(), fixture.tool(), fixture.events());
    }

    @Test
    void shouldResumeWithRemainingTimeAndTokensWithoutChargingHumanWait() {
        ExecutionFixture fixture = executionFixture();
        fixture.status().set("paused");
        AgentExecution paused = new AgentExecution();
        paused.setStatus("paused");
        paused.setStartedAt(LocalDateTime.now().minusDays(3));
        paused.setCompletedAt(paused.getStartedAt().plusSeconds(5));
        paused.setRuntimeElapsedMillis(5_000L);
        paused.setTokenInput(20L);
        paused.setTokenOutput(10L);
        when(fixture.executions().selectById("exec-1")).thenAnswer(invocation ->
                "paused".equals(fixture.status().get()) ? paused : new AgentExecution());
        when(fixture.model().chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("恢复完成")).tokenUsage(new TokenUsage(15, 5)).build());
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("继续");

        AgentExecutionResult result = fixture.engine().resume(fixture.context(), input).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getTokenInput()).isEqualTo(35L);
        assertThat(result.getTokenOutput()).isEqualTo(15L);
        assertThat(fixture.context().getExecutionDeadlineEpochMillis()).isGreaterThan(System.currentTimeMillis());
    }

    @Test
    void shouldRejectExpiredResumeBeforeLoadingToolsOrCallingModel() {
        ExecutionFixture fixture = executionFixture();
        fixture.status().set("paused");
        AgentExecution paused = new AgentExecution();
        paused.setStatus("paused");
        paused.setRuntimeElapsedMillis(Long.MAX_VALUE);
        when(fixture.executions().selectById("exec-1")).thenReturn(paused, new AgentExecution());
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setApprovalId("approval-1");

        AgentExecutionResult result = fixture.engine().resume(fixture.context(), input).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getErrorMessage()).contains("截止时间");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.DEADLINE);
        verifyNoInteractions(fixture.model(), fixture.tool());
    }

    @Test
    void shouldClipOutputLimitAndEstimatePartialProviderUsage() throws Exception {
        AgentLoopState state = new AgentLoopState();
        long now = System.currentTimeMillis();
        AgentExecutionBudget budget = new AgentExecutionBudget(now + 5_000L, 50, 0L, now, state, () -> false);
        AgentModelInvoker invoker = buildModelInvoker();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("答案")).tokenUsage(new TokenUsage(5)).build());
        LangChain4jResolution resolution = new LangChain4jResolution(model, null);
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("完成任务"))
                .maxOutputTokens(1_000).build();

        invoker.invokeWithRetry(resolution, request, AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build(),
                "exec", "agent", "tenant", 1, now, mock(AgentLogService.class), budget);

        verify(model).chat(argThat((ChatRequest actual) -> actual.maxOutputTokens() > 0 && actual.maxOutputTokens() < 50));
        assertThat(state.getTotalTokenInput()).isEqualTo(5L);
        assertThat(state.getTotalTokenOutput()).isPositive();
    }

    @Test
    void shouldNotRetryOrFallbackAfterExecutionDeadline() {
        AgentLoopState state = new AgentLoopState();
        long now = System.currentTimeMillis();
        AgentExecutionBudget budget = new AgentExecutionBudget(now + 350L, 5_000, 0L, now, state, () -> false);
        ChatModel slow = mock(ChatModel.class);
        ChatModel fallback = mock(ChatModel.class);
        when(slow.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(5_000L);
            return ChatResponse.builder().aiMessage(AiMessage.from("迟到结果")).build();
        });
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("完成任务")).build();
        AgentModelInvoker invoker = buildModelInvoker();

        assertThatThrownBy(() -> invoker.invokeChainWithRetry(
                List.of(new LangChain4jResolution(slow, null), new LangChain4jResolution(fallback, null)),
                request, AgentEngineParams.builder().modelCallTimeoutMillis(5_000).build(),
                "exec", "agent", "tenant", 1, now, mock(AgentLogService.class), budget))
                .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class)
                .hasMessageContaining("截止时间");
        verify(slow).chat(any(ChatRequest.class));
        verifyNoInteractions(fallback);
        assertThat(state.getTotalTokenInput()).isPositive();
    }

    @Test
    void shouldStopRetryBackoffAtExecutionDeadline() {
        AgentLoopState state = new AgentLoopState();
        long now = System.currentTimeMillis();
        AgentExecutionBudget budget = new AgentExecutionBudget(now + 350L, 5_000, 0L, now, state, () -> false);
        ChatModel flaky = mock(ChatModel.class);
        when(flaky.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("connection reset"));
        ChatRequest request = ChatRequest.builder().messages(UserMessage.from("完成任务")).build();

        assertThatThrownBy(() -> buildModelInvoker().invokeWithRetry(
                new LangChain4jResolution(flaky, null), request,
                AgentEngineParams.builder().modelCallTimeoutMillis(5_000).build(),
                "exec", "agent", "tenant", 1, now, mock(AgentLogService.class), budget))
                .isInstanceOf(AgentExecutionBudget.BudgetExceededException.class)
                .hasMessageContaining("截止时间");
        verify(flaky).chat(any(ChatRequest.class));
    }

    @Test
    void shouldPreferBlockedToolResultWhenMultipleInterruptActionsExist() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null);

        ToolResult interrupted = engine.findInterruptedToolResult(List.of(
                ToolResult.builder().toolCode("shell_command").success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_PAUSE).build(),
                ToolResult.builder().toolCode("browser_run_code").success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_BLOCK).build()
        ));

        assertThat(interrupted).isNotNull();
        assertThat(interrupted.getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(interrupted.getToolCode()).isEqualTo("browser_run_code");
    }

    @Test
    void shouldPauseSoloRuntimeWhenToolRequiresHumanReview() {
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        AgentExecutionEngine engine = buildMinimalEngine(agentLogService, eventStreamService, null);

        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .runtimeEngine(AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J.getCode()).build();
        ToolResult pausedTool = ToolResult.builder()
                .toolCode("shell_command").success(false)
                .errorMessage("命中高危工具，等待人工复核")
                .controlAction(SecurityComplianceConstant.DECISION_PAUSE).build();
        when(agentLogService.tryPauseExecution("exec-1", "命中高危工具，等待人工复核", 10L, 20L, null, 0L))
                .thenReturn(true);

        AgentExecutionResult result = engine.resolveInterruptedToolResult(
                ctx, "exec-1", "agent-1", "tenant-1", 1,
                List.of(pausedTool), 10L, 20L, "conv-1", 100L);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.PAUSED.getCode());
        assertThat(result.getErrorMessage()).contains("等待人工复核");
        verify(agentLogService).tryPauseExecution("exec-1", "命中高危工具，等待人工复核", 10L, 20L, null, 0L);
        verify(eventStreamService).publishWithPayload(any(), any(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void shouldBuildResumeUserMessageWithOptions() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null);
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("请继续完成剩余测试");
        input.setOptions(Map.of("incidentId", "inc-1", "approved", true));

        String result = engine.buildResumeUserMessage(input);

        assertThat(result).contains("## 人工补充输入");
        assertThat(result).contains("请继续完成剩余测试");
        assertThat(result).contains("incidentId");
        assertThat(result).contains("inc-1");
        assertThat(result).contains("approved");
        assertThat(result).contains("不要重复已完成部分");
    }

    @Test
    void shouldRespectUserMemoryWriteSwitch() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null);
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .userId("user-1")
                .memoryWriteEnabled(false)
                .build();

        assertThat(engine.shouldWriteUserMemory(context)).isFalse();

        context.setMemoryWriteEnabled(null);
        assertThat(engine.shouldWriteUserMemory(context)).isTrue();

        context.setMemoryWriteEnabled(true);
        context.setTemporaryChat(true);
        assertThat(engine.shouldWriteUserMemory(context)).isFalse();
    }

    @Test
    void shouldPersistPendingApprovalBeforePausingToolExecution() {
        PendingToolApprovalService pendingService = mock(PendingToolApprovalService.class);
        PendingToolApproval saved = new PendingToolApproval();
        saved.setId("approval-1");
        when(pendingService.createPending(any(CreatePendingToolApprovalCommand.class))).thenReturn(saved);
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, pendingService);
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .executionMode("plan")
                .build();
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments("{\"path\":\"README.md\"}")
                .build();

        PendingToolApproval approval = engine.createPendingApprovalForTool(
                context, request, ToolIoTypeEnum.WRITE, 3, "conv-1", "batch-1");

        ArgumentCaptor<CreatePendingToolApprovalCommand> captor =
                ArgumentCaptor.forClass(CreatePendingToolApprovalCommand.class);
        verify(pendingService).createPending(captor.capture());
        CreatePendingToolApprovalCommand command = captor.getValue();
        assertThat(approval.getId()).isEqualTo("approval-1");
        assertThat(command.getTenantId()).isEqualTo("tenant-1");
        assertThat(command.getAgentId()).isEqualTo("agent-1");
        assertThat(command.getExecutionId()).isEqualTo("exec-1");
        assertThat(command.getConversationId()).isEqualTo("conv-1");
        assertThat(command.getRoundNum()).isEqualTo(3);
        assertThat(command.getExecutionMode()).isEqualTo("plan");
        assertThat(command.getIoType()).isEqualTo(ToolIoTypeEnum.WRITE);
        assertThat(command.getToolRequest()).isSameAs(request);
        assertThat(command.getBatchId()).isEqualTo("batch-1");
    }

    @Test
    void shouldExecuteApprovedPendingToolCallBeforeContinuingLoop() {
        PendingToolApproval approval = pendingApproval(PendingToolApprovalService.STATUS_APPROVED);
        ToolExecutionRequest rebuilt = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments("{\"path\":\"README.md\"}")
                .build();
        PendingToolApprovalService pendingService = mock(PendingToolApprovalService.class);
        when(pendingService.isApproved(approval)).thenReturn(true);
        when(pendingService.rebuildToolRequest(approval)).thenReturn(rebuilt);
        when(pendingService.consume("approval-1")).thenReturn(true);
        AgentExecutionEngine engine = buildMinimalEngine(mock(AgentLogService.class),
                mock(ExecutionEventStreamService.class), null, pendingService);
        ChatMemory chatMemory = mock(ChatMemory.class);
        dev.langchain4j.service.tool.ToolExecutor executor = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        when(executor.executeWithContext(any(), any())).thenReturn(ToolExecutionResult.builder()
                .isError(false)
                .resultText("{\"ok\":true}")
                .build());

        AgentExecutionResult result = engine.applyPendingApprovalDecision(
                AgentExecutionContext.builder().executionId("exec-1").agentId("agent-1").tenantId("tenant-1").build(),
                roundToolContext("sys.write", executor),
                chatMemory,
                new AgentExecutionInputDTO(),
                approval,
                AgentEngineParams.builder().maxToolResultMessageLength(12000).toolRequestSummaryLimit(800).build(),
                100L,
                "conv-1",
                new AgentLoopState());

        assertThat(result).isNull();
        verify(executor).executeWithContext(eq(rebuilt), any());
        verify(chatMemory).add(argThat((ChatMessage message) ->
                message instanceof ToolExecutionResultMessage resultMessage
                        && resultMessage.id().equals("call-1")
                        && resultMessage.toolName().equals("sys.write")
                        && resultMessage.text().contains("{\"ok\":true}")));
    }

    @Test
    void shouldWriteDeniedPendingToolObservationWithoutExecutingTool() {
        PendingToolApproval approval = pendingApproval(PendingToolApprovalService.STATUS_DENIED);
        approval.setDecisionReason("不希望修改文件");
        ToolExecutionRequest rebuilt = ToolExecutionRequest.builder()
                .id("call-1")
                .name("sys.write")
                .arguments("{\"path\":\"README.md\"}")
                .build();
        PendingToolApprovalService pendingService = mock(PendingToolApprovalService.class);
        when(pendingService.isApproved(approval)).thenReturn(false);
        when(pendingService.isDenied(approval)).thenReturn(true);
        when(pendingService.rebuildToolRequest(approval)).thenReturn(rebuilt);
        AgentExecutionEngine engine = buildMinimalEngine(mock(AgentLogService.class),
                mock(ExecutionEventStreamService.class), null, pendingService);
        ChatMemory chatMemory = mock(ChatMemory.class);
        dev.langchain4j.service.tool.ToolExecutor executor = mock(dev.langchain4j.service.tool.ToolExecutor.class);

        AgentExecutionResult result = engine.applyPendingApprovalDecision(
                AgentExecutionContext.builder().executionId("exec-1").agentId("agent-1").tenantId("tenant-1").build(),
                roundToolContext("sys.write", executor),
                chatMemory,
                new AgentExecutionInputDTO(),
                approval,
                AgentEngineParams.builder().maxToolResultMessageLength(12000).toolRequestSummaryLimit(800).build(),
                100L,
                "conv-1",
                new AgentLoopState());

        assertThat(result).isNull();
        verify(executor, never()).executeWithContext(any(), any());
        verify(chatMemory).add(argThat((ChatMessage message) ->
                message instanceof ToolExecutionResultMessage resultMessage
                        && resultMessage.id().equals("call-1")
                        && resultMessage.text().contains("用户拒绝执行该工具")
                        && resultMessage.text().contains("不希望修改文件")));
    }

    @Test
    void shouldResumeWholeToolRoundAcrossTwoApprovalsInOriginalOrder() {
        PendingToolApprovalMapper approvalMapper = mock(PendingToolApprovalMapper.class);
        Map<String, PendingToolApproval> approvals = new LinkedHashMap<>();
        when(approvalMapper.insert(any(PendingToolApproval.class))).thenAnswer(invocation -> {
            PendingToolApproval approval = invocation.getArgument(0);
            approval.setId("approval-" + (approvals.size() + 1));
            approvals.put(approval.getId(), approval);
            return 1;
        });
        when(approvalMapper.selectById(anyString())).thenAnswer(invocation -> approvals.get(invocation.getArgument(0)));
        when(approvalMapper.update(any(), any())).thenReturn(1);
        ToolApprovalBatchMapper batchMapper = mock(ToolApprovalBatchMapper.class);
        AtomicReference<ToolApprovalBatch> storedBatch = new AtomicReference<>();
        when(batchMapper.insert(any(ToolApprovalBatch.class))).thenAnswer(invocation -> {
            ToolApprovalBatch inserted = invocation.getArgument(0);
            inserted.setId("batch-1");
            storedBatch.set(inserted);
            return 1;
        });
        when(batchMapper.selectById("batch-1")).thenAnswer(invocation -> storedBatch.get());
        when(batchMapper.updateById(any(ToolApprovalBatch.class))).thenReturn(1);
        ExecutionFixture fixture = executionFixture(
                new PendingToolApprovalService(approvalMapper), new ToolApprovalBatchService(batchMapper));
        when(fixture.tool().executeWithContext(any(), any())).thenAnswer(invocation -> {
            ToolExecutionRequest request = invocation.getArgument(0);
            return ToolExecutionResult.builder().resultText("结果 " + request.id()).build();
        });
        List<ToolExecutionRequest> calls = List.of(
                approvalRequest("read", "fixture_read"),
                approvalRequest("edit", "fixture_write"),
                approvalRequest("deny", "fixture_write"),
                approvalRequest("limit", "fixture_read"),
                approvalRequest("unknown", "fixture_unknown"));
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(calls)).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("已完成")).build());

        assertThat(fixture.engine().execute(fixture.context()).join().getStatus()).isEqualTo("paused");
        assertThat(storedBatch.get().getCallStates()).extracting(call -> call.get("callId"))
                .containsExactly("read", "edit", "deny", "limit", "unknown");
        verifyNoInteractions(fixture.tool());

        PendingToolApproval edited = approvals.get("approval-1");
        edited.setDecisionStatus(PendingToolApprovalService.STATUS_EDITED);
        edited.setToolArgumentsText("{\"value\":\"edited\"}");
        AgentExecutionInputDTO approve = new AgentExecutionInputDTO();
        approve.setApprovalId(edited.getId());
        AgentExecutionContext firstResume = approvalRunContext();
        assertThat(fixture.engine().resume(firstResume, approve).join().getStatus()).isEqualTo("paused");
        assertThat(approvals).containsKeys("approval-1", "approval-2");
        assertThat(approvals.get("approval-2").getBatchId()).isEqualTo("batch-1");

        // 模拟已派发但结果写入失败的持久化状态，下一次恢复不得重放。
        Map<String, Object> firstCall = storedBatch.get().getCallStates().getFirst();
        firstCall.put("status", ToolApprovalBatchService.CALL_STATUS_DISPATCHING);
        firstCall.remove("resultText");

        PendingToolApproval denied = approvals.get("approval-2");
        denied.setDecisionStatus(PendingToolApprovalService.STATUS_DENIED);
        AgentExecutionInputDTO deny = new AgentExecutionInputDTO();
        deny.setApprovalId(denied.getId());
        AgentExecutionContext secondResume = approvalRunContext();
        assertThat(fixture.engine().resume(secondResume, deny).join().getStatus()).isEqualTo("completed");

        ArgumentCaptor<ToolExecutionRequest> dispatched = ArgumentCaptor.forClass(ToolExecutionRequest.class);
        verify(fixture.tool(), times(2)).executeWithContext(dispatched.capture(), any());
        assertThat(dispatched.getAllValues()).extracting(ToolExecutionRequest::id).containsExactly("read", "edit");
        assertThat(dispatched.getAllValues().getLast().arguments()).contains("edited");
        ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(fixture.model(), times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().getLast().messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast)
                .map(ToolExecutionResultMessage::id).toList())
                .containsExactly("read", "edit", "deny", "limit", "unknown");
        assertThat(firstCall.get("resultText").toString()).contains("执行结果未知");
    }

    private ToolExecutionRequest approvalRequest(String id, String toolName) {
        return ToolExecutionRequest.builder().id(id).name(toolName).arguments("{}").build();
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    private AgentExecutionEngine buildMinimalEngine(AgentLogService agentLogService,
                                                     ExecutionEventStreamService eventStreamService,
                                                     QualityOrchestrator qualityOrchestrator) {
        return buildMinimalEngine(agentLogService, eventStreamService, qualityOrchestrator,
                mock(PendingToolApprovalService.class));
    }

    @Test
    void shouldResolveDeadlineAndTokenBudgetFromSandboxPolicy() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, mock(PendingToolApprovalService.class));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .sandboxPolicy(SandboxPolicy.builder()
                        .maxExecutionMinutes(45)
                        .maxTokenBudget(99_000L)
                        .build())
                .build();

        assertThat(engine.resolveMaxExecutionMinutes(context)).isEqualTo(45);
        assertThat(engine.resolveMaxTokenBudget(context)).isEqualTo(99_000L);
    }

    @Test
    void shouldFallBackToDefaultBudgetWhenPolicyMissing() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, mock(PendingToolApprovalService.class));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .build();

        assertThat(engine.resolveMaxExecutionMinutes(context)).isEqualTo(30);
        assertThat(engine.resolveMaxTokenBudget(context)).isEqualTo(200_000L);
    }

    @Test
    void shouldInjectPendingSteeringIntoChatMemory() {
        ExecutionSteeringService steeringService = mock(ExecutionSteeringService.class);
        ExecutionSteering steering = new ExecutionSteering();
        steering.setId("steering-1");
        steering.setSeq(1);
        steering.setContent("只做 A，不要做 B");
        ExecutionSteering newer = new ExecutionSteering();
        newer.setId("steering-2");
        newer.setSeq(2);
        newer.setContent("补充测试");
        when(steeringService.findPending("exec-1")).thenReturn(List.of(steering, newer));
        UserMessage applied = UserMessage.from(ExecutionSteeringService.buildSteeringWrapper(steering.getContent()));
        UserMessage appliedNewer = UserMessage.from(ExecutionSteeringService.buildSteeringWrapper(newer.getContent()));
        when(steeringService.applyAndArchive(steering, "conversation")).thenReturn(Optional.of(applied), Optional.empty());
        when(steeringService.applyAndArchive(newer, "conversation")).thenReturn(Optional.of(appliedNewer), Optional.empty());
        AgentExecutionEngine engine = buildMinimalEngine(mock(AgentLogService.class),
                mock(ExecutionEventStreamService.class), null, mock(PendingToolApprovalService.class), steeringService);
        ArchivedChatMemory chatMemory = mock(ArchivedChatMemory.class);

        assertThat(engine.consumePendingSteeringIfAny("exec-1", "agent-1", "tenant-1", "conversation",
                chatMemory, 1, 100L)).isTrue();
        assertThat(engine.consumePendingSteeringIfAny("exec-1", "agent-1", "tenant-1", "conversation",
                chatMemory, 2, 100L)).isFalse();

        var order = inOrder(steeringService, chatMemory);
        order.verify(steeringService).applyAndArchive(steering, "conversation");
        order.verify(chatMemory).addArchivedUserInput(applied);
        order.verify(steeringService).applyAndArchive(newer, "conversation");
        order.verify(chatMemory).addArchivedUserInput(appliedNewer);
        verify(chatMemory, times(2)).addArchivedUserInput(any());
    }

    @Test
    void shouldPrefixConversationIdWithTenant() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, mock(PendingToolApprovalService.class));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .build();

        assertThat(engine.resolveConversationId(context)).startsWith("tenant-1:");
    }

    @Test
    void shouldNotDoublePrefixConversationId() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, mock(PendingToolApprovalService.class));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .conversationId("tenant-1:conv-1")
                .build();

        assertThat(engine.resolveConversationId(context)).isEqualTo("tenant-1:conv-1");
    }

    @Test
    void shouldKeepTeamConversationIdUnprefixed() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null, mock(PendingToolApprovalService.class));
        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .conversationId("team:exec-1:member-1")
                .build();

        assertThat(engine.resolveConversationId(context)).isEqualTo("team:exec-1:member-1");
    }

    private AgentExecutionEngine buildMinimalEngine(AgentLogService agentLogService,
                                                     ExecutionEventStreamService eventStreamService,
                                                     QualityOrchestrator qualityOrchestrator,
                                                     PendingToolApprovalService pendingToolApprovalService) {
        return buildMinimalEngine(agentLogService, eventStreamService, qualityOrchestrator, pendingToolApprovalService,
                mock(ExecutionSteeringService.class));
    }

    private AgentExecutionEngine buildMinimalEngine(AgentLogService agentLogService,
                                                     ExecutionEventStreamService eventStreamService,
                                                     QualityOrchestrator qualityOrchestrator,
                                                     PendingToolApprovalService pendingToolApprovalService,
                                                     ExecutionSteeringService executionSteeringService) {
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentLoopShadowReviewService shadowReviewService = new AgentLoopShadowReviewService(checker, Runnable::run);
        AgentLoopCompletionHandler completionHandler = new AgentLoopCompletionHandler(null, checker, qualityOrchestrator);
        AgentLoopToolHandler toolHandler = new AgentLoopToolHandler(new ObjectMapper());
        AgentModelInvoker modelInvoker = buildModelInvoker();
        return new AgentExecutionEngine(
                agentLogService, null, null, mock(AgentExecutionMapper.class), new ObjectMapper(),
                eventStreamService, null, null, null,
                modelInvoker, toolHandler, completionHandler, checker, shadowReviewService, null,
                null, mock(AgentTraceService.class), new AgentHookExecutor(List.of(), null),
                mock(AgentContextBudgetService.class), mock(MultimodalMessageBuilder.class),
                mock(ObjectProvider.class), new ToolExecutionGate(mock(ToolApprovalAmendmentService.class)),
                new ExecutionModePolicyResolver(), new ExecutionModePromptContributor(), pendingToolApprovalService,
                mock(ToolApprovalBatchService.class),
                executionSteeringService, testRecovery().service());
    }

    @Test
    void shouldOnlyProjectTheCurrentAuthoredInputInOptInSoloExecution() {
        String system = "系统任务规则";
        String input = "测试任务";
        String canonical = system + "\n\n---\n\n## 当前任务上下文\n" + input;
        var range = new ContextInjector.TaskContextRange(system.length(), canonical.length(), input);
        for (String scenario : List.of("default", "fresh", "team-child", "attachments", "temporary")) {
            ExecutionFixture fixture = executionFixture();
            ReflectionTestUtils.setField(fixture.engine(), "taskContextProjectionEnabled", !"default".equals(scenario));
            switch (scenario) {
                case "team-child" -> fixture.context().setParentExecutionId("team-parent");
                case "attachments" -> fixture.context().setAttachmentIds(List.of("attachment"));
                case "temporary" -> fixture.context().setTemporaryChat(true);
                default -> { }
            }
            when(fixture.injector().buildSystemPromptDetail(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(new ContextInjector.PromptBuildResult(canonical, 0, 0, 0, canonical.length(), 100,
                            null, 0, 0, 0, 0, 0, 0, range));
            when(fixture.model().chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("已完成测试任务")).build());

            fixture.engine().execute(fixture.context()).join();

            ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(fixture.model()).chat(request.capture());
            assertThat(SystemMessage.findFirst(request.getValue().messages()).orElseThrow())
                    .as(scenario).isEqualTo(SystemMessage.from("fresh".equals(scenario) ? system : canonical));
            assertThat(request.getValue().messages()).contains(UserMessage.from(input));
            assertThat(request.getValue().toolSpecifications()).extracting(ToolSpecification::name)
                    .containsExactly("fixture_read", "fixture_write");
        }
    }

    @Test
    void shouldProjectOnlyTheMatchingFreshTeamTaskAndKeepItsArchiveSynthetic() {
        String stable = "系统规则\n成员专属上下文\n已确认记忆";
        String input = "测试任务";
        String system = stable + "\n\n---\n\n## 当前任务上下文\n" + input;
        for (String scenario : List.of("enabled", "disabled", "wrong-role", "wrong-task", "attachment", "temporary", "compressed")) {
            var fixture = executionFixture();
            var context = fixture.context();
            markTeamTaskFixture(context);
            ReflectionTestUtils.setField(fixture.engine(), "taskContextProjectionEnabled", !"disabled".equals(scenario));
            var range = "compressed".equals(scenario) ? null
                    : new ContextInjector.TaskContextRange(stable.length(), system.length(), input);
            switch (scenario) {
                case "wrong-role" -> context.setTeamMemberRoleName("另一个角色");
                case "wrong-task" -> context.setInputPrompt("另一个任务");
                case "attachment" -> context.setAttachmentIds(List.of("attachment"));
                case "temporary" -> context.setTemporaryChat(true);
                default -> { }
            }
            when(fixture.injector().buildSystemPromptDetail(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                    .thenReturn(new ContextInjector.PromptBuildResult(system, 0, 0, 0, system.length(), 100,
                            null, 0, 0, 0, 0, 0, 0, range));
            when(fixture.model().chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("已完成测试任务")).build());

            fixture.engine().execute(context).join();

            var request = ArgumentCaptor.forClass(ChatRequest.class);
            verify(fixture.model()).chat(request.capture());
            assertThat(SystemMessage.findFirst(request.getValue().messages())).as(scenario)
                    .contains(SystemMessage.from("enabled".equals(scenario) ? stable : system));
            assertThat(request.getValue().messages()).contains(UserMessage.from(input));
            assertThat(request.getValue().toolSpecifications()).extracting(ToolSpecification::name)
                    .containsExactly("fixture_read", "fixture_write");
            verify(fixture.memory()).appendMessages(anyString(), eq(context.getExecutionId()),
                    eq(List.of(new ArchivedMessage(UserMessage.from(input), null))));
        }
    }

    @Test
    void shouldPreserveTheCanonicalTaskDuringTeamQualityReflection() {
        String stable = "系统规则\n成员专属上下文\n已确认记忆";
        String input = "测试任务";
        String system = stable + "\n\n---\n\n## 当前任务上下文\n" + input;
        var range = new ContextInjector.TaskContextRange(stable.length(), system.length(), input);
        var checker = spy(new AgentLoopQualityChecker(null));
        doReturn(new QualityReflectionFeedback("缺少结构", "完善结果", Map.of()))
                .when(checker).buildFeedback(any(), eq("候选结果"), any(), anyInt(), any());
        var fixture = executionFixture(checker, 1);
        markTeamTaskFixture(fixture.context());
        ReflectionTestUtils.setField(fixture.engine(), "taskContextProjectionEnabled", true);
        when(fixture.injector().buildSystemPromptDetail(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ContextInjector.PromptBuildResult(system, 0, 0, 0, system.length(), 100, null,
                        0, 0, 0, 0, 0, 0, range));
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("候选结果")).build(),
                        ChatResponse.builder().aiMessage(AiMessage.from("修订结果")).build());

        fixture.engine().execute(fixture.context()).join();

        var requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(fixture.model(), times(2)).chat(requests.capture());
        assertThat(requests.getAllValues()).extracting(request -> SystemMessage.findFirst(request.messages()).orElseThrow())
                .containsExactly(SystemMessage.from(stable), SystemMessage.from(system));
    }

    private void markTeamTaskFixture(AgentExecutionContext context) {
        context.setRuntimeEngine(AgentRuntimeEngineEnum.TEAM_LANGGRAPH4J.getCode());
        context.setParentExecutionId("parent-1");
        context.setTeamAgentId(context.getAgentId());
        context.setTeamMemberId("member-1");
        context.setTeamMemberRoleName("测试员");
        context.setTeamMemberRoleType("member");
        context.setTeamTaskContextProjection(new AgentExecutionContext.TeamTaskContextProjection(
                context.getExecutionId(), context.getTenantId(), context.getTeamAgentId(), context.getParentExecutionId(),
                context.getTeamMemberId(), context.getConversationId(), context.getTeamMemberRoleName(),
                context.getTeamMemberRoleType(), context.getInputPrompt()));
    }

    @Test
    void shouldFailEmptyCandidateWithStableReasonWithoutAnotherModelCall() {
        ExecutionFixture fixture = executionFixture();
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(" ")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.EMPTY_RESPONSE);
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
        verify(fixture.logs()).tryFinalizeWithEvent(eq("exec-1"), eq("failed"), anyString(),
                anyLong(), anyLong(), isNull(), isNull(), argThat(event -> event.getRoundNum() == 1
                        && event.getPayload().equals(Map.of("endReason", "EMPTY_RESPONSE", "degraded", false))));
    }

    @Test
    void shouldFailCompletionClaimWhenConfiguredDesktopVerificationIsMissing() {
        ExecutionFixture fixture = executionFixture();
        fixture.context().setDesktopVerificationCommand("mvn clean package");
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("所有测试已通过")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.DESKTOP_VERIFICATION_MISSING);
        assertThat(result.getOutputResult()).isNull();
        assertThat(result.getErrorMessage()).isEqualTo(AgentLogService.DESKTOP_VERIFICATION_REQUIRED);
        verify(fixture.logs()).hasCurrentDesktopVerification(fixture.context());
    }

    @Test
    void shouldCorrectUnverifiedDesktopCompletionOnceWithoutReportingSuccess() {
        var fixture = desktopCompletionFixture();
        var requests = new ArrayList<ChatRequest>();
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return ChatResponse.builder().aiMessage(AiMessage.from("任务已完成")).build();
        });

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().messages().getLast()).isInstanceOfSatisfying(UserMessage.class,
                message -> assertThat(message.singleText()).contains("缺少有效的最终验证回执"));
        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.DESKTOP_VERIFICATION_MISSING);
        verifyNoInteractions(fixture.tool());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldAcceptCorrectedDesktopCompletionAfterVerificationThroughBoundTool(boolean teamOwner) {
        var fixture = desktopCompletionFixture();
        if (teamOwner) {
            fixture.context().setParentExecutionId("team-root");
            fixture.context().setTeamDesktopVerificationOwner(true);
        }
        var verified = new AtomicBoolean();
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenAnswer(ignored -> verified.get());
        var verifyRequest = ToolExecutionRequest.builder().id("verify-1").name("sys_bash")
                .arguments("{\"command\":\"mvn clean package\",\"workingDirectory\":\".\"}").build();
        when(fixture.tool().executeWithContext(eq(verifyRequest), any())).thenAnswer(ignored -> {
            verified.set(true);
            return ToolExecutionResult.builder().isError(false).resultText("{\"exitCode\":0}").build();
        });
        when(fixture.model().chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("准备交付")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from(List.of(verifyRequest))).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("已完成构建验收")).build());

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getOutputResult()).isEqualTo("已完成构建验收");
        verify(fixture.tool()).executeWithContext(eq(verifyRequest), any());
        verify(fixture.model(), times(3)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldRemindTeamVerificationOwnerBeforeRoundLimitWithoutLocalWrite() {
        var fixture = desktopCompletionFixture();
        fixture.context().setParentExecutionId("team-root");
        fixture.context().setTeamDesktopVerificationOwner(true);
        var requests = new ArrayList<ChatRequest>();
        var inspect = ToolExecutionRequest.builder().id("inspect").name("sys_bash")
                .arguments("{\"command\":\"git status\",\"workingDirectory\":\".\"}").build();
        when(fixture.tool().executeWithContext(eq(inspect), any())).thenReturn(
                ToolExecutionResult.builder().isError(false).resultText("{\"exitCode\":0}").build());
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return ChatResponse.builder().aiMessage(requests.size() == 1
                    ? AiMessage.from(inspect) : AiMessage.from("已检查工作区")).build();
        });

        fixture.engine().execute(fixture.context()).join();

        verify(fixture.session()).augmentSystemPrompt(anyString(), eq(2));
        assertThat(requests.get(1).messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.text()).contains("当前验收余下约三轮"));
    }

    @Test
    void shouldPublishToolStepsOnTeamRootWithoutReplacingDesktopReceipt() {
        var fixture = desktopCompletionFixture();
        fixture.context().setParentExecutionId("team-root");
        fixture.context().setTeamAgentId("team-agent");
        fixture.context().setTeamMemberRoleName("测试");
        fixture.context().setTeamDesktopVerificationOwner(true);
        var command = ToolExecutionRequest.builder().id("build-1").name("sys_bash")
                .arguments("{\"command\":\"mvn clean package\",\"workingDirectory\":\".\"}").build();
        var receipt = new ObjectMapper().getNodeFactory().textNode("{\"exitCode\":0}");
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenReturn(true);
        when(fixture.tool().executeWithContext(eq(command), any())).thenReturn(ToolExecutionResult.builder()
                .isError(false).resultText(receipt.textValue())
                .result(ToolResult.builder().callId(command.id()).toolCode("sys.bash")
                        .success(true).result(receipt).desktopCallId(UUID.randomUUID()).build()).build());
        when(fixture.model().chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(command)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("已通过验证")).build());

        assertThat(fixture.engine().execute(fixture.context()).join().getStatus()).isEqualTo("completed");
        verify(fixture.tool()).executeWithContext(eq(command), any());
        verify(fixture.events()).publishActivity(eq("team-root"), eq("team-agent"), eq("tenant-1"),
                argThat(activity -> activity.kind() == AgentExecutionActivity.Kind.COMMAND
                        && activity.status() == AgentExecutionActivity.Status.RUNNING));
        verify(fixture.events()).publishActivity(eq("team-root"), eq("team-agent"), eq("tenant-1"),
                argThat(activity -> activity.kind() == AgentExecutionActivity.Kind.COMMAND
                        && activity.status() == AgentExecutionActivity.Status.COMPLETED
                        && "测试".equals(activity.source()) && "0".equals(activity.fields().get("exitCode"))));
    }

    @Test
    void shouldLeaveDesktopVerificationToDesignatedTeamMember() {
        var fixture = desktopCompletionFixture();
        fixture.context().setParentExecutionId("team-root");
        fixture.context().setDesktopVerificationDelegated(true);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("编码交接")).build());

        assertThat(fixture.engine().execute(fixture.context()).join().getStatus()).isEqualTo("completed");

        verify(fixture.model()).chat(any(ChatRequest.class));
        verify(fixture.logs(), never()).hasCurrentDesktopVerification(any());
    }

    @Test
    void shouldPromptForProgressAfterRepeatedConfirmedDesktopReads() {
        var fixture = desktopCompletionFixture();
        var rounds = spy(roundToolContext("sys_read", fixture.tool()));
        doReturn(true).when(rounds).isDesktopTool("sys_read");
        when(fixture.session().buildRoundContext(any(), anyInt())).thenReturn(rounds);
        when(fixture.params().load(any(), any())).thenReturn(AgentEngineParams.builder()
                .maxRounds(6).maxMessages(16).maxToolCallsPerRound(2).maxQualityReflections(0)
                .modelCallTimeoutMillis(8_000L).build());
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenReturn(true);
        when(fixture.tool().executeWithContext(any(), any())).thenAnswer(invocation -> {
            ToolExecutionRequest request = invocation.getArgument(0);
            return ToolExecutionResult.builder().isError(false).resultText("完整读取内容")
                    .result(ToolResult.builder().callId(request.id()).toolCode("sys.read").success(true)
                            .desktopCallId(UUID.randomUUID()).result(new ObjectMapper().getNodeFactory()
                                    .textNode("完整读取内容")).build())
                    .attributes(Map.of("desktopReadEvidenceHash", "相同文件与内容"))
                    .build();
        });
        var requests = new ArrayList<ChatRequest>();
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            int round = requests.size();
            var message = round <= 4 ? AiMessage.from(List.of(ToolExecutionRequest.builder()
                    .id("read-" + round).name("sys_read").arguments("{\"path\":\"README.md\"}").build()))
                    : AiMessage.from("已完成");
            return ChatResponse.builder().aiMessage(message).build();
        });

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(requests).hasSize(5);
        assertThat(requests.getLast().messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.text()).contains("连续读取已核对的相同文件版本"));
        verify(fixture.tool(), times(4)).executeWithContext(any(), any());
    }

    private ExecutionFixture desktopCompletionFixture() {
        var fixture = executionFixture();
        fixture.context().setDesktopVerificationCommand("mvn clean package");
        fixture.context().setInputContext(Map.of("desktopDeviceId", "device-1", "desktopWorkspaceId", "local"));
        var rounds = spy(roundToolContext("sys_bash", fixture.tool()));
        doReturn(true).when(rounds).isDesktopTool("sys_bash");
        when(fixture.session().buildRoundContext(any(), anyInt())).thenReturn(rounds);
        when(fixture.params().load(any(), any())).thenReturn(AgentEngineParams.builder()
                .maxRounds(4).maxMessages(12).maxToolCallsPerRound(2).maxQualityReflections(0)
                .modelCallTimeoutMillis(8_000L).build());
        return fixture;
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldMoveFromRepeatedReadsToRepairAndVerificationWithinOriginalBudget(boolean sameReadRepeated) {
        var fixture = desktopCompletionFixture();
        var specs = List.of("sys_edit", "sys_read", "sys_bash").stream()
                .map(name -> ToolSpecification.builder().name(name).description("测试工具").build()).toList();
        var bound = ToolServiceContext.builder().availableTools(specs).toolSpecifications(specs).effectiveTools(specs)
                .toolExecutors(Map.of("sys_edit", fixture.tool(), "sys_read", fixture.tool(), "sys_bash", fixture.tool())).build();
        var rounds = spy(new AgentToolSessionFactory.RoundToolContext(bound, null, Map.of()));
        for (var spec : specs) doReturn(true).when(rounds).isDesktopTool(spec.name());
        when(fixture.session().buildRoundContext(any(), anyInt())).thenReturn(rounds);
        when(fixture.params().load(any(), any())).thenReturn(AgentEngineParams.builder()
                .maxRounds(10).maxMessages(32).maxToolCallsPerRound(4).maxQualityReflections(0)
                .modelCallTimeoutMillis(8_000L).build());
        var edit = ToolExecutionRequest.builder().id("edit-1").name("sys_edit")
                .arguments("{\"path\":\"QuoteTest.java\"}").build();
        var firstRead = ToolExecutionRequest.builder().id("read-1").name("sys_read")
                .arguments("{\"path\":\"QuoteTest.java\"}").build();
        var repeat = firstRead.toBuilder().id("read-2").build();
        var newRead = firstRead.toBuilder().id("read-3").arguments("{\"path\":\"Quote.java\"}").build();
        var build = ToolExecutionRequest.builder().id("build-1").name("sys_bash")
                .arguments("{\"command\":\"mvn clean package\",\"workingDirectory\":\".\"}").build();
        var verified = new AtomicBoolean();
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenAnswer(ignored -> verified.get());
        when(fixture.tool().executeWithContext(any(), any())).thenAnswer(invocation -> {
            ToolExecutionRequest request = invocation.getArgument(0);
            boolean failed = request.id().equals(build.id());
            boolean read = "sys_read".equals(request.name());
            if ("build-2".equals(request.id())) verified.set(true);
            String output = failed ? "[ERROR] QuoteTest.java:[130] no suitable method\n".repeat(40) : "实际执行结果";
            UUID desktopCallId = UUID.randomUUID();
            var attrs = read ? Map.<String, Object>of("desktopReadEvidenceHash", request.arguments())
                    : failed ? Map.<String, Object>of(
                            "desktopMavenModelSummary", "{\"exitCode\":1,\"errors\":\"QuoteTest.java:[130] no suitable method\"}",
                            "desktopMavenSourceFailure", true, "desktopCallId", desktopCallId.toString()) : Map.<String, Object>of();
            return ToolExecutionResult.builder().isError(failed).resultText(output).attributes(attrs)
                    .result(ToolResult.builder().callId(request.id()).toolCode(request.name().replace('_', '.'))
                            .success(!failed).desktopCallId(desktopCallId)
                            .result(new ObjectMapper().getNodeFactory().textNode(output)).build()).build();
        });
        var plan = new ArrayList<>(List.of(AiMessage.from(edit), AiMessage.from(firstRead)));
        if (sameReadRepeated) {
            plan.add(AiMessage.from(List.of(repeat, newRead)));
        } else {
            plan.add(AiMessage.from(newRead));
            plan.add(AiMessage.from(firstRead.toBuilder().id("read-4").arguments("{\"path\":\"Input.java\"}").build()));
        }
        int buildIndex = plan.size();
        plan.addAll(List.of(AiMessage.from(build), AiMessage.from(edit.toBuilder().id("edit-2").build()),
                AiMessage.from(build.toBuilder().id("cli-1").arguments("{\"command\":\"java -jar target/quote.jar\"}").build()),
                AiMessage.from(build.toBuilder().id("build-2").build()), AiMessage.from("真实验证通过")));
        var requests = new ArrayList<ChatRequest>();
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0);
            int index = requests.size();
            requests.add(request);
            if (index == buildIndex) assertThat(request.messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                    message -> assertThat(message.text()).contains(
                            sameReadRepeated ? "连续读取已核对的相同文件版本" : "本机文件修改后已连续多轮读取",
                            "修复后立即重验"));
            if (index == buildIndex + 1) {
                assertThat(request.messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                        message -> assertThat(message.text()).contains("将同一文件的相关修复合成一次编辑"));
                assertThat(request.messages().getLast()).isInstanceOfSatisfying(ToolExecutionResultMessage.class,
                        message -> assertThat(message.text()).contains("QuoteTest.java:[130] no suitable method"));
            }
            if (index == 5 && index != buildIndex + 1) assertThat(request.messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                    message -> assertThat(message.text()).contains("剩余额度进入验证阶段"));
            if (index == 7 && !sameReadRepeated) {
                assertThat(request.messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                        message -> assertThat(message.text()).contains("留出一轮执行最终验证命令"));
            }
            assertThat(request.messages()).noneMatch(message -> message instanceof UserMessage user
                    && user.hasSingleText() && (user.singleText().contains("剩余额度进入验证阶段")
                    || user.singleText().contains("连续读取已核对") || user.singleText().contains("本机文件修改后")));
            return ChatResponse.builder().aiMessage(plan.get(index)).tokenUsage(new TokenUsage(21_000, 200)).build();
        });

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getTokenInput() + result.getTokenOutput()).isEqualTo(21_200 * plan.size());
        assertThat(requests).hasSize(plan.size());
        verify(fixture.tool()).executeWithContext(eq(newRead), any());
        verify(fixture.tool()).executeWithContext(eq(build.toBuilder().id("build-2").build()), any());
    }

    @Test
    void shouldProtectCurrentDesktopVerificationWhenModelBudgetIsTight() {
        var fixture = desktopCompletionFixture();
        fixture.context().setParentExecutionId("team-root");
        fixture.context().setTeamDesktopVerificationOwner(true);
        var build = ToolExecutionRequest.builder().id("build").name("sys_bash")
                .arguments("{\"command\":\"mvn clean package\",\"workingDirectory\":\".\"}").build();
        var verified = new AtomicBoolean();
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenAnswer(ignored -> verified.get());
        when(fixture.tool().executeWithContext(eq(build), any())).thenAnswer(ignored -> {
            verified.set(true);
            return ToolExecutionResult.builder().isError(false).resultText("{\"exitCode\":0}").build();
        });
        var requests = new ArrayList<ChatRequest>();
        when(fixture.model().chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return ChatResponse.builder().aiMessage(requests.size() == 1
                    ? AiMessage.from(build) : AiMessage.from("按实际结果交付")).build();
        });

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(requests).hasSize(2);
        assertThat(requests.getLast().messages().getFirst()).isInstanceOfSatisfying(SystemMessage.class,
                message -> assertThat(message.text()).contains("现有最终验证回执有效", "后续 Shell 会使现有最终验证回执失效"));
        verify(fixture.tool(), times(1)).executeWithContext(any(), any());
    }

    @Test
    void shouldStopRepeatingQualityReflectionForIdenticalCandidate() {
        AgentLoopQualityChecker checker = spy(new AgentLoopQualityChecker(null));
        doReturn(new QualityReflectionFeedback("缺少结构", "完善结果", Map.of()))
                .when(checker).buildFeedback(any(), eq("相同结果"), any(), anyInt(), any());
        ExecutionFixture fixture = executionFixture(checker, 3);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("相同结果")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.QUALITY_LIMIT);
        assertThat(result.getOutputResult()).isEqualTo("相同结果");
        verify(fixture.model(), times(2)).chat(any(ChatRequest.class));
        verify(checker, times(1)).buildFeedback(any(), anyString(), any(), anyInt(), any());
    }

    @Test
    void shouldApplyOutputSecurityBeforeConsideringReflection() {
        AgentLoopQualityChecker checker = spy(new AgentLoopQualityChecker(null));
        ExecutionFixture fixture = executionFixture(checker, 3);
        SecurityRuntimeGuardService guard = mock(SecurityRuntimeGuardService.class);
        when(fixture.guardProvider().getIfAvailable()).thenReturn(guard);
        SecurityCheckDecisionVO decision = new SecurityCheckDecisionVO();
        decision.setDecision(SecurityComplianceConstant.DECISION_BLOCK);
        decision.setMessage("内容被策略拦截");
        when(guard.evaluate(any(), isNull())).thenReturn(decision);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("候选内容")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.SECURITY_BLOCKED);
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
        verify(checker, never()).buildFeedback(any(), anyString(), any(), anyInt(), any());
    }

    @Test
    void shouldFailClosedWhenOutputGuardThrows() {
        ExecutionFixture fixture = executionFixture();
        SecurityRuntimeGuardService guard = mock(SecurityRuntimeGuardService.class);
        when(fixture.guardProvider().getIfAvailable()).thenReturn(guard);
        when(guard.evaluate(any(), isNull())).thenThrow(new IllegalStateException("policy store unavailable"));
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("候选内容")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.INTERNAL_ERROR);
        assertThat(result.getErrorMessage()).contains("输出安全检查失败");
    }

    @Test
    void shouldGuardRawCandidateAndPersistSanitizedDisplayContent() {
        AgentLoopQualityChecker checker = spy(new AgentLoopQualityChecker(null));
        ExecutionFixture fixture = executionFixture(checker, 0);
        doReturn("已清洗展示内容").when(checker).sanitize("原始候选 /tmp/internal.txt");
        SecurityRuntimeGuardService guard = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO allow = new SecurityCheckDecisionVO();
        allow.setDecision(SecurityComplianceConstant.DECISION_ALLOW);
        when(guard.evaluate(any(), isNull())).thenReturn(allow);
        when(fixture.guardProvider().getIfAvailable()).thenReturn(guard);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("原始候选 /tmp/internal.txt")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        ArgumentCaptor<com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest> checked =
                ArgumentCaptor.forClass(com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest.class);
        verify(guard).evaluate(checked.capture(), isNull());
        assertThat(checked.getValue().getContent()).isEqualTo("原始候选 /tmp/internal.txt");
        assertThat(result.getOutputResult()).isEqualTo("已清洗展示内容");
    }

    @Test
    void shouldSkipConvergenceWhenRoundLimitHasNoEvidence() {
        ExecutionFixture fixture = executionFixture();
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("未完成片段"))
                        .finishReason(FinishReason.LENGTH).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.ROUND_LIMIT);
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldDegradeFromEvidenceWhenConvergenceAlsoReturnsEmpty() {
        ExecutionFixture fixture = executionFixture();
        stubCompletionEvidence(fixture);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(" ")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.EMPTY_RESPONSE);
        assertThat(result.isDegraded()).isTrue();
        assertThat(result.getOutputResult()).contains("降级收敛");
        verify(fixture.model(), times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldNotStartConvergenceAfterExhaustingTokenBudget() {
        ExecutionFixture fixture = executionFixture();
        stubCompletionEvidence(fixture);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("超量输出"))
                        .tokenUsage(new TokenUsage(500_000, 0)).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.TOKEN_BUDGET);
        assertThat(result.isDegraded()).isTrue();
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldPersistVerifiedDesktopBudgetFallbackAsDegraded() {
        ExecutionFixture fixture = executionFixture();
        fixture.context().setDesktopVerificationCommand("mvn clean package");
        fixture.context().setInputContext(Map.of("desktopDeviceId", "device-1", "desktopWorkspaceId", "local"));
        stubCompletionEvidence(fixture);
        when(fixture.logs().hasCurrentDesktopVerification(fixture.context())).thenReturn(true);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("超量输出"))
                        .tokenUsage(new TokenUsage(500_000, 0)).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed_with_fallback");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.TOKEN_BUDGET);
        assertThat(result.isDegraded()).isTrue();
        verify(fixture.logs()).tryFinalizeWithEvent(eq("exec-1"), eq("completed_with_fallback"),
                isNull(), anyLong(), anyLong(), anyString(), isNull(), any());
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldWaitForShadowReviewAtMostOncePerCandidate() {
        AgentLoopShadowReviewService shadowReview = mock(AgentLoopShadowReviewService.class);
        CompletableFuture<QualityReflectionFeedback> pending = new CompletableFuture<>();
        when(shadowReview.submitStructuralReview(any(), eq("候选内容"), eq(0), any())).thenReturn(pending);
        ExecutionFixture fixture = executionFixture(new AgentLoopQualityChecker(null), 2, shadowReview);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("候选内容")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.MODEL_COMPLETED);
        verify(shadowReview).await(eq(pending), longThat(wait -> wait > 0 && wait <= 350));
        verify(fixture.model(), times(1)).chat(any(ChatRequest.class));
        assertThat(pending.isCancelled()).isTrue();
    }

    private void stubCompletionEvidence(ExecutionFixture fixture) {
        when(fixture.memory().loadWindow(anyString(), anyString(), anyInt())).thenReturn(List.of(
                UserMessage.from("核对项一"), AiMessage.from("已确认第一项"),
                UserMessage.from("核对项二"), AiMessage.from("已确认第二项")
        ));
    }

    private ExecutionFixture executionFixture() {
        return executionFixture(new AgentLoopQualityChecker(null), 0);
    }

    private ExecutionFixture executionFixture(PendingToolApprovalService approvals, ToolApprovalBatchService approvalBatches) {
        return executionFixture(new AgentLoopQualityChecker(null), 0, null, approvals, approvalBatches);
    }

    @Test
    void shouldResumeModelWithPersistedToolReceiptWithoutRedispatch() {
        ExecutionFixture fixture = executionFixture();
        fixture.status().set("running");
        var request = ToolExecutionRequest.builder().id("previous-write").name("fixture_write").arguments("{}").build();
        var receipt = ToolExecutionResultMessage.from(request, "已确认结果");
        when(fixture.recoveryLease().checkpoint()).thenReturn(new AgentRunRecovery.Checkpoint(1, true));
        when(fixture.recoveryLease().dispatches()).thenReturn(List.of(
                new AgentRunRecovery.Dispatch(request.id(), request.name(), receipt, false)));
        when(fixture.memory().loadWindow(anyString(), anyString(), anyInt()))
                .thenReturn(List.of(UserMessage.from("继续原任务"), AiMessage.from(List.of(request))));
        when(fixture.params().load(any(), any())).thenReturn(AgentEngineParams.builder().maxRounds(3)
                .maxMessages(8).maxToolCallsPerRound(3).modelCallTimeoutMillis(8_000L).build());
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("任务已完成")).build());

        AgentExecutionResult result = fixture.engine().recover(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        var sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(fixture.model()).chat(sent.capture());
        assertThat(sent.getValue().messages()).contains(receipt);
        verifyNoInteractions(fixture.tool());
        verify(fixture.memory()).appendMissingToolResults(anyString(), anyString(), eq(List.of(receipt)));
    }

    @Test
    void shouldStopRecoveryForUncertainToolResultBeforeCallingTheModel() {
        ExecutionFixture fixture = executionFixture();
        fixture.status().set("running");
        when(fixture.recoveryLease().checkpoint()).thenReturn(new AgentRunRecovery.Checkpoint(1, true));
        when(fixture.recoveryLease().dispatches()).thenReturn(List.of(
                new AgentRunRecovery.Dispatch("previous-write", "fixture_write", null, false)));

        AgentExecutionResult result = fixture.engine().recover(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.RECOVERY_REVIEW_REQUIRED);
        assertThat(result.getErrorMessage()).contains("previous-write");
        verifyNoInteractions(fixture.model(), fixture.tool());
    }

    @Test
    void shouldTryOneBudgetedAcceptanceRepairWithoutTools() {
        ExecutionFixture fixture = executionFixture();
        fixture.context().setAcceptance(new AgentAcceptance(Map.of("/ok", "boolean"),
                Map.of("/ok", BooleanNode.TRUE), null));
        when(fixture.params().load(any(), any())).thenReturn(AgentEngineParams.builder().maxRounds(3)
                .maxMessages(12).maxToolCallsPerRound(3).modelCallTimeoutMillis(8_000L).build());
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ok\":false}")).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ok\":true}")).build());

        var result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("completed");
        var requests = ArgumentCaptor.forClass(ChatRequest.class);
        verify(fixture.model(), times(2)).chat(requests.capture());
        assertThat(requests.getAllValues().getLast().toolSpecifications()).isEmpty();
        assertThat(requests.getAllValues().getLast().messages().toString()).contains("/ok");
        verifyNoInteractions(fixture.tool());
    }

    @Test
    void shouldRejectACompletedCandidateThatFailsTheExecutionContract() {
        ExecutionFixture fixture = executionFixture();
        fixture.context().setAcceptance(new AgentAcceptance(Map.of("/ok", "boolean")));
        when(fixture.logs().tryFinalizeWithEvent(eq("exec-1"), eq("failed"), anyString(), anyLong(), anyLong(),
                isNull(), eq(AgentAcceptance.Status.REJECTED), any())).thenReturn(true);
        when(fixture.model().chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{\"ok\":\"yes\"}")).build());

        AgentExecutionResult result = fixture.engine().execute(fixture.context()).join();

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getEndReason()).isEqualTo(AgentLoopEndReason.ACCEPTANCE_REJECTED);
        verify(fixture.logs(), never()).tryFinalizeWithEvent(eq("exec-1"), eq("completed"), any(), any(), any(), any(), any(), any());
    }

    private ExecutionFixture executionFixture(AgentLoopQualityChecker checker, int maxReflections) {
        return executionFixture(checker, maxReflections, null);
    }

    private ExecutionFixture executionFixture(AgentLoopQualityChecker checker, int maxReflections,
                                               AgentLoopShadowReviewService shadowReview) {
        return executionFixture(checker, maxReflections, shadowReview,
                mock(PendingToolApprovalService.class), mock(ToolApprovalBatchService.class));
    }

    private ExecutionFixture executionFixture(AgentLoopQualityChecker checker, int maxReflections,
                                               AgentLoopShadowReviewService shadowReview,
                                               PendingToolApprovalService approvals,
                                               ToolApprovalBatchService approvalBatches) {
        AgentLogService logs = mock(AgentLogService.class);
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        ExecutionEventStreamService events = mock(ExecutionEventStreamService.class);
        ChatModel model = mock(ChatModel.class);
        AtomicReference<String> status = new AtomicReference<>("queued");
        when(executions.selectById("exec-1")).thenAnswer(invocation -> {
            AgentExecution execution = new AgentExecution();
            execution.setStatus(status.get());
            return execution;
        });
        when(logs.tryTransitionExecutionStatus(eq("exec-1"), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> status.compareAndSet(invocation.getArgument(1), invocation.getArgument(2)));
        when(logs.tryFinalizeWithEvent(eq("exec-1"), anyString(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> status.compareAndSet("running", invocation.getArgument(1)));
        when(logs.tryPauseExecution(eq("exec-1"), any(), anyLong(), anyLong(), any(), anyLong()))
                .thenAnswer(invocation -> status.compareAndSet("running", "paused"));

        AIModelRouter router = mock(AIModelRouter.class);
        AiModelConfig config = AiModelConfig.builder().provider("openai").modelId("cancel-fixture").build();
        when(router.resolveRouteChainForExecution("cancel-fixture", "medium"))
                .thenReturn(List.of(new LangChain4jResolution(model, config)));
        ContextInjector injector = mock(ContextInjector.class);
        when(injector.buildSystemPromptDetail(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new ContextInjector.PromptBuildResult(
                        "执行任务", 0, 0, 0, 4, 4, null, 0, 0, 0, 0, 0, 0));
        AgentToolSessionFactory sessions = mock(AgentToolSessionFactory.class);
        AgentToolSessionFactory.AgentToolSession session = mock(AgentToolSessionFactory.AgentToolSession.class);
        when(sessions.openSession(any(), anyString())).thenReturn(session);
        when(session.augmentSystemPrompt(anyString(), anyInt())).thenAnswer(invocation -> invocation.getArgument(0));
        dev.langchain4j.service.tool.ToolExecutor tool = mock(dev.langchain4j.service.tool.ToolExecutor.class);
        List<ToolSpecification> specifications = List.of("fixture_read", "fixture_write").stream()
                .map(name -> ToolSpecification.builder().name(name).description("测试工具").build()).toList();
        var toolContext = ToolServiceContext.builder().availableTools(specifications).toolSpecifications(specifications)
                .effectiveTools(specifications).toolExecutors(Map.of("fixture_read", tool, "fixture_write", tool)).build();
        when(session.buildRoundContext(any(), anyInt())).thenReturn(new AgentToolSessionFactory.RoundToolContext(
                toolContext, null, Map.of("fixture_read", ToolIoTypeEnum.READ, "fixture_write", ToolIoTypeEnum.WRITE)));
        AgentEngineConfigLoader params = mock(AgentEngineConfigLoader.class);
        when(params.load(any(), any())).thenReturn(AgentEngineParams.builder()
                .maxRounds(1).maxMessages(8).maxToolCallsPerRound(3).maxQualityReflections(maxReflections)
                .shadowQualityReviewEnabled(shadowReview != null).shadowQualityReviewAwaitMillis(350L)
                .modelCallTimeoutMillis(8_000L).build());
        MultimodalMessageBuilder userMessages = mock(MultimodalMessageBuilder.class);
        when(userMessages.buildForExecution(anyString(), any(), anyBoolean(), any(), any(), any()))
                .thenReturn(UserMessage.from("测试任务"));
        AgentHookExecutor hooks = new AgentHookExecutor(List.of(), null);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> guardProvider = mock(ObjectProvider.class);
        SecurityRuntimeGuardService defaultOutputGuard = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO allow = new SecurityCheckDecisionVO();
        allow.setDecision(SecurityComplianceConstant.DECISION_ALLOW);
        when(defaultOutputGuard.evaluate(any(), isNull())).thenReturn(allow);
        when(guardProvider.getIfAvailable()).thenReturn(defaultOutputGuard);
        AgentModelInvoker invoker = buildModelInvoker();
        CompositeChatMemoryStore store = mock(CompositeChatMemoryStore.class);
        List<ChatMessage> persistedMessages = new ArrayList<>();
        when(store.loadWindow(anyString(), anyString(), anyInt())).thenAnswer(invocation -> List.copyOf(persistedMessages));
        doAnswer(invocation -> {
            List<com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage> entries = invocation.getArgument(2);
            entries.forEach(entry -> persistedMessages.add(entry.message()));
            return null;
        }).when(store).appendMessages(anyString(), anyString(), anyList());
        doAnswer(invocation -> {
            List<ToolExecutionResultMessage> entries = invocation.getArgument(2);
            entries.forEach(entry -> {
                boolean exists = persistedMessages.stream().anyMatch(message ->
                        message instanceof ToolExecutionResultMessage result && entry.id().equals(result.id()));
                if (!exists) {
                    persistedMessages.add(entry);
                }
            });
            return null;
        }).when(store).appendMissingToolResults(anyString(), anyString(), anyList());
        var recovery = testRecovery();
        AgentExecutionEngine engine = new AgentExecutionEngine(
                logs, injector, router, executions, new ObjectMapper(),
                events, sessions, store, params,
                invoker, new AgentLoopToolHandler(new ObjectMapper()),
                new AgentLoopCompletionHandler(invoker, checker, null), checker,
                shadowReview != null ? shadowReview : new AgentLoopShadowReviewService(checker, Runnable::run),
                new AgentLoopDetectionService(),
                mock(ToolOutputMaskingService.class), mock(AgentTraceService.class), hooks,
                mock(AgentContextBudgetService.class), userMessages, guardProvider,
                new ToolExecutionGate(mock(ToolApprovalAmendmentService.class)), new ExecutionModePolicyResolver(),
                new ExecutionModePromptContributor(), approvals, approvalBatches, mock(ExecutionSteeringService.class), recovery.service());
        return new ExecutionFixture(engine, approvalRunContext(), status, model, tool, logs, events, executions,
                guardProvider, store, injector, session, params, recovery.lease());
    }

    private RecoveryFixture testRecovery() {
        AgentRunRecovery recovery = mock(AgentRunRecovery.class);
        AgentRunRecovery.Lease lease = mock(AgentRunRecovery.Lease.class);
        when(lease.checkpoint()).thenReturn(new AgentRunRecovery.Checkpoint(0, true));
        when(recovery.claim(any(), anyString(), anyBoolean())).thenReturn(lease);
        return new RecoveryFixture(recovery, lease);
    }

    private record RecoveryFixture(AgentRunRecovery service, AgentRunRecovery.Lease lease) {}

    private AgentExecutionContext approvalRunContext() {
        return AgentExecutionContext.builder().executionId("exec-1")
                .agentId("agent-1").tenantId("tenant-1").userId("user-1")
                .model("cancel-fixture").inputPrompt("测试任务").conversationId("tenant-1:conv-1").build();
    }

    private record ExecutionFixture(AgentExecutionEngine engine, AgentExecutionContext context,
                                    AtomicReference<String> status, ChatModel model,
                                    dev.langchain4j.service.tool.ToolExecutor tool,
                                    AgentLogService logs, ExecutionEventStreamService events,
                                    AgentExecutionMapper executions,
                                    ObjectProvider<SecurityRuntimeGuardService> guardProvider,
                                    CompositeChatMemoryStore memory, ContextInjector injector,
                                    AgentToolSessionFactory.AgentToolSession session, AgentEngineConfigLoader params,
                                    AgentRunRecovery.Lease recoveryLease) {
    }

    private AgentToolSessionFactory.RoundToolContext roundToolContext(
            String toolName,
            dev.langchain4j.service.tool.ToolExecutor executor) {
        ToolSpecification specification = ToolSpecification.builder()
                .name(toolName)
                .description("测试工具")
                .build();
        ToolServiceContext toolServiceContext = ToolServiceContext.builder()
                .availableTools(List.of(specification))
                .toolSpecifications(List.of(specification))
                .effectiveTools(List.of(specification))
                .toolExecutors(Map.of(toolName, executor))
                .build();
        return new AgentToolSessionFactory.RoundToolContext(
                toolServiceContext, null, Map.of(toolName, ToolIoTypeEnum.WRITE));
    }

    private PendingToolApproval pendingApproval(String status) {
        PendingToolApproval approval = new PendingToolApproval();
        approval.setId("approval-1");
        approval.setTenantId("tenant-1");
        approval.setAgentId("agent-1");
        approval.setExecutionId("exec-1");
        approval.setConversationId("conv-1");
        approval.setRoundNum(2);
        approval.setToolCallId("call-1");
        approval.setToolCode("sys.write");
        approval.setToolName("sys.write");
        approval.setToolArgumentsText("{\"path\":\"README.md\"}");
        approval.setDecisionStatus(status);
        return approval;
    }

    private AgentModelInvoker buildModelInvoker() {
        return buildModelInvoker(mock(LangChain4jModelFactory.class));
    }

    private AgentModelInvoker buildModelInvoker(LangChain4jModelFactory factory) {
        return new AgentModelInvoker(
                new AgentHookExecutor(List.of(), null),
                mock(ModelMetricsTracker.class),
                mock(RouteAnalysisService.class),
                mock(AgentTraceService.class),
                factory
        );
    }
}
