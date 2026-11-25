package com.schemaplexai.service.agent.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schemaplexai.common.enums.AgentLoopLogTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.model.vo.monitor.ModelAttemptMetadata;
import com.schemaplexai.service.agent.hook.AgentHookContext;
import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.agent.hook.AgentHookType;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AI 模型调用器
 *
 * <p>封装模型调用的超时控制、重试、降级链切换逻辑，从 AgentExecutionEngine 中拆分出来</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentModelInvoker {

    private static final int MAX_CONCURRENT_MODEL_CALLS = 50;
    private static final Semaphore EVAL_MODEL_CALL_SEMAPHORE = new Semaphore(4, true);
    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final Semaphore MODEL_CALL_SEMAPHORE = new Semaphore(MAX_CONCURRENT_MODEL_CALLS, true);
    private static final long TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long TEMP_UNAVAILABLE_ON_FATAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    /** 降级链中的前置候选只做快速探测，避免真实工作流被长超时拖住。 */
    private static final long FALLBACK_CANDIDATE_TIMEOUT_CAP_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long CANCELLATION_POLL_MILLIS = 500L;
    private static final Cache<CooldownKey, Long> TEMP_UNAVAILABLE_UNTIL = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMillis(TEMP_UNAVAILABLE_ON_FATAL_MILLIS))
            .maximumSize(200)
            .build();

    private final AgentHookExecutor hookExecutor;
    private final ModelMetricsTracker modelMetricsTracker;
    private final RouteAnalysisService routeAnalysisService;
    private final AgentTraceService agentTraceService;
    private final LangChain4jModelFactory modelFactory;
    private final TokenEstimatorSupport tokenEstimator = new TokenEstimatorSupport();
    private final AgentModelRequestPlanner requestPlanner = new AgentModelRequestPlanner(tokenEstimator);

    @Value("${schemaplexai.agent.model.request.components.enabled:false}")
    private boolean logRequestComponents;

    public enum ModelProgress {
        THINKING("正在分析任务"), RESPONDING("正在整理本轮回复"), TOOL_CALL("正在准备工具调用");

        private final String summary;

        ModelProgress(String summary) {
            this.summary = summary;
        }

        public String summary() {
            return summary;
        }
    }

    // =========================================================================
    //  模型链调用（含降级切换）
    // =========================================================================

    /**
     * 按模型链顺序调用，失败时自动切换到下一个降级模型
     *
     * @throws Exception 所有模型均失败时抛出最后一个异常
     */
    public ModelCallResult invokeChainWithRetry(List<LangChain4jResolution> modelChain,
                                                 ChatRequest request,
                                                 AgentEngineParams params,
                                                 String executionId, String agentId, String tenantId,
                                                 int round, long startMs,
                                                 AgentLogService agentLogService) throws Exception {
        return invokeChainWithRetry(modelChain, request, params, executionId, agentId, tenantId, round, startMs,
                agentLogService, null, null);
    }

    public ModelCallResult invokeChainWithRetry(List<LangChain4jResolution> modelChain,
                                                ChatRequest request, AgentEngineParams params,
                                                String executionId, String agentId, String tenantId, int round, long startMs,
                                                AgentLogService agentLogService, Consumer<ModelProgress> onProgress) throws Exception {
        return invokeChainWithRetry(modelChain, request, params, executionId, agentId, tenantId, round, startMs,
                agentLogService, null, onProgress);
    }

    ModelCallResult invokeChainWithRetry(List<LangChain4jResolution> modelChain,
                                         ChatRequest request, AgentEngineParams params,
                                         String executionId, String agentId, String tenantId, int round, long startMs,
                                         AgentLogService agentLogService, AgentExecutionBudget budget) throws Exception {
        return invokeChainWithRetry(modelChain, request, params, executionId, agentId, tenantId, round, startMs,
                agentLogService, budget, null);
    }

    ModelCallResult invokeChainWithRetry(List<LangChain4jResolution> modelChain,
                                         ChatRequest request, AgentEngineParams params,
                                         String executionId, String agentId, String tenantId, int round, long startMs,
                                         AgentLogService agentLogService, AgentExecutionBudget budget,
                                         Consumer<ModelProgress> onProgress) throws Exception {
        if (!acquirePermit(budget)) {
            throw new BusinessException(ResultCode.AGENT_BUSY, "模型调用并发已达上限，请稍后重试");
        }
        try {
            // 触发 BEFORE_MODEL_CALL Hook
            AgentHookContext beforeCtx = AgentHookContext.builder()
                    .executionId(executionId).agentId(agentId).tenantId(tenantId)
                    .round(round).chatRequest(request).build();
            hookExecutor.fire(AgentHookType.BEFORE_MODEL_CALL, beforeCtx, agentId);

            ModelCallResult result = doInvokeChainWithRetry(modelChain, request, params,
                    executionId, agentId, tenantId, round, startMs, agentLogService, budget, onProgress);

            // 触发 AFTER_MODEL_CALL Hook
            AgentHookContext afterCtx = AgentHookContext.builder()
                    .executionId(executionId).agentId(agentId).tenantId(tenantId)
                    .round(round).chatRequest(request).chatResponse(result.response()).build();
            hookExecutor.fire(AgentHookType.AFTER_MODEL_CALL, afterCtx, agentId);

            return result;
        } finally {
            modelSemaphore(budget).release();
        }
    }

    private ModelCallResult doInvokeChainWithRetry(List<LangChain4jResolution> modelChain,
                                                   ChatRequest request,
                                                   AgentEngineParams params,
                                                   String executionId, String agentId, String tenantId,
                                                   int round, long startMs,
                                                   AgentLogService agentLogService, AgentExecutionBudget budget,
                                                   Consumer<ModelProgress> onProgress) throws Exception {
        Exception lastError = null;
        boolean attempted = false;
        for (int i = 0; i < modelChain.size(); i++) {
            LangChain4jResolution resolution = modelChain.get(i);
            boolean isLastCandidate = i == modelChain.size() - 1;
            CooldownKey cooldownKey = cooldownKey(tenantId, resolution);
            long remainingUnavailableMillis = remainingUnavailableMillis(cooldownKey);
            if (remainingUnavailableMillis > 0) {
                if (!isLastCandidate) {
                    appendFallbackLog(executionId, agentId, tenantId, round, startMs, agentLogService,
                            "模型当前处于冷却期，跳过并切换至下一个降级模型: provider=" + providerOf(resolution)
                                    + ", modelId=" + modelIdOf(resolution)
                                    + ", remainingMs=" + remainingUnavailableMillis);
                }
                continue;
            }
            attempted = true;
            LocalDateTime candidateStartedAt = LocalDateTime.now();
            long candidateStartMs = System.currentTimeMillis();
            try {
                int maxRetries = isLastCandidate ? resolveMaxRetries(resolution, params) : 0;
                long timeoutMillis = resolveChainTimeoutMillis(resolution, params, isLastCandidate);
                ChatResponse response = invokeWithRetry(resolution, request, params,
                        executionId, agentId, tenantId, round, startMs, agentLogService,
                        maxRetries, timeoutMillis, budget, "inference", onProgress);
                long latencyMs = System.currentTimeMillis() - candidateStartMs;
                long inputTokens = extractInputTokens(response);
                long outputTokens = extractOutputTokens(response);
                BigDecimal cost = calculateCost(resolution, inputTokens, outputTokens);
                modelMetricsTracker.recordSuccess(configIdOf(resolution), latencyMs, inputTokens, outputTokens, cost);
                routeAnalysisService.recordDecision(
                        tenantId,
                        agentId,
                        executionId,
                        configIdOf(resolution),
                        "fallback_chain",
                        modelChain.stream().map(this::configIdOf).toList(),
                        i == 0 ? "primary_selected" : "fallback_selected_" + i,
                        latencyMs,
                        inputTokens + outputTokens,
                        cost
                );
                if (budget == null) agentTraceService.recordModelSpan(
                        executionId,
                        tenantId,
                        agentId,
                        providerOf(resolution) + ":" + modelIdOf(resolution),
                        candidateStartedAt,
                        latencyMs,
                        inputTokens,
                        outputTokens,
                        cost,
                        "SUCCESS"
                );
                return new ModelCallResult(response, resolution);
            } catch (AgentModelRequestPlanner.IncompatibleCandidateException mismatch) {
                lastError = mismatch;
                appendFallbackLog(executionId, agentId, tenantId, round, startMs, agentLogService,
                        "跳过不兼容的候选模型: " + modelIdOf(resolution) + ", reason=" + mismatch.getMessage());
            } catch (AgentExecutionBudget.BudgetExceededException | AgentRunRecovery.LostOwnershipException
                     | CancellationException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                markTemporaryUnavailable(cooldownKey, e);
                long latencyMs = System.currentTimeMillis() - candidateStartMs;
                modelMetricsTracker.recordFailure(configIdOf(resolution), latencyMs);
                if (budget == null) agentTraceService.recordModelSpan(
                        executionId,
                        tenantId,
                        agentId,
                        providerOf(resolution) + ":" + modelIdOf(resolution),
                        candidateStartedAt,
                        latencyMs,
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        "FAILED"
                );
                if (!isLastCandidate) {
                    appendFallbackLog(executionId, agentId, tenantId, round, startMs, agentLogService,
                            "模型调用失败，切换至下一个降级模型: provider=" + resolution.config().getProvider()
                                    + ", modelId=" + resolution.config().getModelId()
                                    + ", error=" + resolveExMsg(e));
                }
            }
        }
        if (!attempted) {
            throw new IllegalStateException("AI 调用失败，所有候选模型当前处于冷却期");
        }
        if (lastError instanceof AgentModelRequestPlanner.IncompatibleCandidateException mismatch
                && mismatch.isTokenBudgetLimited()) {
            throw new AgentExecutionBudget.BudgetExceededException(mismatch.getMessage());
        }
        throw lastError != null ? lastError : new IllegalStateException("AI 调用失败");
    }

    // =========================================================================
    //  单模型调用（含重试）
    // =========================================================================

    /**
     * 对单个模型进行调用，失败时按配置重试
     *
     * @throws Exception 超出重试次数后抛出最后一个异常
     */
    public ChatResponse invokeWithRetry(LangChain4jResolution resolution,
                                         ChatRequest request,
                                         AgentEngineParams params,
                                         String executionId, String agentId, String tenantId,
                                         int round, long startMs,
                                         AgentLogService agentLogService) throws Exception {
        return invokeWithRetry(resolution, request, params,
                executionId, agentId, tenantId, round, startMs, agentLogService,
                resolveMaxRetries(resolution, params),
                resolveTimeoutMillis(resolution, params.getModelCallTimeoutMillis()), null, "direct", null);
    }

    ChatResponse invokeWithRetry(LangChain4jResolution resolution, ChatRequest request, AgentEngineParams params,
                                 String executionId, String agentId, String tenantId, int round, long startMs,
                                 AgentLogService agentLogService, AgentExecutionBudget budget) throws Exception {
        return invokeWithRetry(resolution, request, params, executionId, agentId, tenantId, round, startMs,
                agentLogService, budget, "auxiliary");
    }

    ChatResponse invokeWithRetry(LangChain4jResolution resolution, ChatRequest request, AgentEngineParams params,
                                 String executionId, String agentId, String tenantId, int round, long startMs,
                                 AgentLogService agentLogService, AgentExecutionBudget budget, String phase) throws Exception {
        if (!acquirePermit(budget)) {
            throw new BusinessException(ResultCode.AGENT_BUSY, "模型调用并发已达上限，请稍后重试");
        }
        try {
            return invokeWithRetry(resolution, request, params, executionId, agentId, tenantId, round, startMs,
                    agentLogService, resolveMaxRetries(resolution, params),
                    resolveTimeoutMillis(resolution, params.getModelCallTimeoutMillis()), budget, phase, null);
        } finally {
            modelSemaphore(budget).release();
        }
    }

    private ChatResponse invokeWithRetry(LangChain4jResolution resolution,
                                         ChatRequest request,
                                         AgentEngineParams params,
                                         String executionId, String agentId, String tenantId,
                                         int round, long startMs,
                                         AgentLogService agentLogService,
                                         int maxRetries,
                                         long timeoutMillis,
                                         AgentExecutionBudget budget,
                                         String phase,
                                         Consumer<ModelProgress> onProgress) throws Exception {
        Exception lastError = null;
        int maxAttempts = maxRetries + 1;
        long retryIntervalMillis = resolveRetryIntervalMillis(resolution);
        Consumer<ModelProgress> streamingProgress = resolution.config() == null ? null : onProgress;
        int dispatchCount = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptStarted = System.nanoTime();
            try {
                ChatResponse response;
                try {
                    response = invokeAttempt(resolution, request, timeoutMillis, budget,
                            new AttemptTrace(executionId, agentId, tenantId, round, phase, ++dispatchCount), streamingProgress);
                } catch (Exception error) {
                    long remaining = timeoutMillis - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStarted);
                    if (streamingProgress == null || !rejectsStreaming(error) || remaining <= 0) {
                        throw error;
                    }
                    streamingProgress = null;
                    response = invokeAttempt(resolution, request, remaining, budget,
                            new AttemptTrace(executionId, agentId, tenantId, round, phase, ++dispatchCount), null);
                }
                validateResponse(response);
                return response;
            } catch (AgentModelRequestPlanner.IncompatibleCandidateException | AgentExecutionBudget.BudgetExceededException
                     | AgentRunRecovery.LostOwnershipException | CancellationException | InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (!isRetryable(e) || attempt > maxRetries) throw e;
                String errMsg = resolveExMsg(e);
                log.warn("AI 调用失败，准备重试: executionId={}, round={}, attempt={}, error={}",
                        executionId, round, attempt, errMsg);
                agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                        AgentLoopLogTypeEnum.MODEL_RETRY.getCode(), round, null,
                        "模型调用失败，准备第 " + attempt + " 次重试: " + errMsg, null, elapsed(startMs));
                try {
                    backoff(retryIntervalMillis * attempt, budget);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("AI 调用失败");
    }

    private ChatResponse invokeAttempt(LangChain4jResolution resolution, ChatRequest request, long timeoutMillis,
                                       AgentExecutionBudget budget, AttemptTrace trace,
                                       Consumer<ModelProgress> onProgress) throws Exception {
        if (budget == null && onProgress == null) return invokeWithTimeout(resolution, request, timeoutMillis);
        return invokeWithTimeout(resolution, request, timeoutMillis, budget, budget == null ? null : trace, onProgress);
    }

    private boolean rejectsStreaming(Exception error) {
        Throwable cause = error instanceof InvalidRequestException || error instanceof InternalServerException
                ? error.getCause() : error;
        if (!(cause instanceof HttpException http)) return false;
        String message = String.valueOf(http.getMessage()).toLowerCase(Locale.ROOT);
        return http.statusCode() == 405 || http.statusCode() == 501
                || ((http.statusCode() == 400 || http.statusCode() == 422) && message.contains("stream")
                && containsAny(message, "not support", "unsupported", "unknown parameter", "unrecognized"));
    }

    // =========================================================================
    //  超时控制
    // =========================================================================

    /**
     * 带超时控制的模型调用
     */
    public ChatResponse invokeWithTimeout(LangChain4jResolution resolution,
                                           ChatRequest request,
                                           long timeoutMillis) throws Exception {
        return invokeWithTimeout(resolution, request, timeoutMillis, null, null, null);
    }

    private ChatResponse invokeWithTimeout(LangChain4jResolution resolution, ChatRequest request,
                                            long timeoutMillis, AgentExecutionBudget budget, AttemptTrace trace,
                                            Consumer<ModelProgress> onProgress) throws Exception {
        if (budget != null) {
            budget.checkActive();
        }
        ChatRequest projected = requestPlanner.fit(request, resolution.config(),
                budget != null ? budget.remainingTokens() : Integer.MAX_VALUE);
        String attemptId = budget == null ? null : UUID.randomUUID().toString();
        var modelAttempt = budget == null ? null : budget.prepare(resolution.config(), projected, requestPlanner,
                attemptId, trace.round(), trace.phase());
        ChatRequest effectiveRequest = modelAttempt == null ? projected : modelAttempt.request();
        LocalDateTime dispatchedAt = LocalDateTime.now();
        long dispatchedNanos = System.nanoTime();
        ChatResponse response = null;
        var dispatched = new AtomicBoolean();
        try {
            response = onProgress == null
                    ? invokeSynchronous(resolution, effectiveRequest, timeoutMillis, budget, dispatched)
                    : invokeStreaming(resolution, effectiveRequest, timeoutMillis, budget, onProgress, dispatched);
            if (budget != null) {
                budget.checkModelOwnership();
            }
            AiMessage message = response == null ? null : response.aiMessage();
            if (onProgress != null && AiModelConfig.PROTOCOL_OPENAI.equals(resolution.config().getProtocol())
                    && message != null && message.thinking() != null) {
                // OpenAI 兼容的思考内容仅用于阶段识别，存档保持原有语义。
                response = response.toBuilder().aiMessage(message.toBuilder().thinking(null).build()).build();
            }
            return response;
        } catch (TimeoutException e) {
            long seconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(timeoutMillis));
            throw new TimeoutException("AI 调用超时，超过 " + seconds + " 秒");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new IllegalStateException(cause != null ? cause.getMessage() : "AI 调用失败", cause);
        } finally {
            if (budget != null) {
                var usage = budget.recordAttempt(resolution.config(), modelAttempt, response, tokenEstimator, attemptId,
                        dispatched.get());
                recordAttemptTrace(trace, attemptId, resolution, dispatchedAt,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - dispatchedNanos), response, usage);
                if (logRequestComponents && log.isInfoEnabled()) {
                    recordRequestComponents(attemptId, resolution, modelAttempt);
                }
            }
        }
    }

    private void authorizeDispatch(AgentExecutionBudget budget, AtomicBoolean dispatched) {
        if (budget != null) {
            budget.checkModelOwnership();
            if (budget.remainingMillis() == 0) {
                throw new AgentExecutionBudget.BudgetExceededException("执行截止时间已过");
            }
        }
        dispatched.set(true);
    }

    private ChatResponse invokeSynchronous(LangChain4jResolution resolution, ChatRequest request, long timeoutMillis,
                                           AgentExecutionBudget budget, AtomicBoolean dispatched) throws Exception {
        Future<ChatResponse> future = MODEL_CALL_EXECUTOR.submit(() -> {
            authorizeDispatch(budget, dispatched);
            return resolution.model().chat(request);
        });
        try {
            return awaitResponse(future, timeoutMillis, budget);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private ChatResponse invokeStreaming(LangChain4jResolution resolution, ChatRequest request, long timeoutMillis,
                                         AgentExecutionBudget budget, Consumer<ModelProgress> onProgress, AtomicBoolean dispatched) throws Exception {
        var completion = new CompletableFuture<ChatResponse>();
        var handle = new AtomicReference<StreamingHandle>();
        var reported = new AtomicInteger(-1);
        Future<?> dispatch = MODEL_CALL_EXECUTOR.submit(() -> {
            try {
                var model = modelFactory.getOrCreateStreaming(resolution.config());
                authorizeDispatch(budget, dispatched);
                model.chat(request, new StreamingChatResponseHandler() {
                    private void report(ModelProgress progress, StreamingHandle streamingHandle) {
                        handle.compareAndSet(null, streamingHandle);
                        if (completion.isCancelled() && streamingHandle != null) {
                            streamingHandle.cancel();
                        } else if (!completion.isDone()
                                && reported.getAndAccumulate(progress.ordinal(), Math::max) < progress.ordinal()) {
                            onProgress.accept(progress);
                        }
                    }

                    @Override
                    public void onPartialThinking(PartialThinking thinking, PartialThinkingContext context) {
                        report(ModelProgress.THINKING, context.streamingHandle());
                    }

                    @Override
                    public void onPartialResponse(PartialResponse response, PartialResponseContext context) {
                        report(ModelProgress.RESPONDING, context.streamingHandle());
                    }

                    @Override
                    public void onPartialToolCall(PartialToolCall call, PartialToolCallContext context) {
                        report(ModelProgress.TOOL_CALL, context.streamingHandle());
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        completion.complete(response);
                    }

                    @Override
                    public void onError(Throwable error) {
                        completion.completeExceptionally(error);
                    }
                });
            } catch (RuntimeException error) {
                completion.completeExceptionally(error);
            }
        });
        try {
            return awaitResponse(completion, timeoutMillis, budget);
        } finally {
            if (completion.cancel(true)) {
                dispatch.cancel(true);
                StreamingHandle streamingHandle = handle.get();
                if (streamingHandle != null) streamingHandle.cancel();
            }
        }
    }

    private void recordRequestComponents(String attemptId, LangChain4jResolution resolution,
                                         AgentExecutionBudget.ModelAttempt attempt) {
        try {
            var config = resolution.config();
            long system = 0, user = 0, toolResults = 0, other = 0;
            long assistantText = 0;
            Map<String, Long> resultTokensByTool = new TreeMap<>();
            Map<String, Long> argumentTokensByTool = new TreeMap<>();
            for (ChatMessage message : attempt.request().messages()) {
                int tokens = tokenEstimator.estimateMessage(config, message);
                switch (message) {
                    case SystemMessage ignored -> system += tokens;
                    case UserMessage ignored -> user += tokens;
                    case ToolExecutionResultMessage result -> {
                        toolResults += tokens;
                        resultTokensByTool.merge(toolPayloadCategory(result.toolName()), (long) tokens, Long::sum);
                    }
                    case AiMessage assistant -> {
                        other += tokens;
                        assistantText += tokenEstimator.estimateText(config, assistant.text());
                        if (assistant.hasToolExecutionRequests()) {
                            for (var call : assistant.toolExecutionRequests()) {
                                long arguments = tokenEstimator.estimateText(config, call.arguments());
                                argumentTokensByTool.merge(toolPayloadCategory(call.name()), arguments, Long::sum);
                            }
                        }
                    }
                    default -> other += tokens;
                }
            }
            long framing = tokenEstimator.estimateMessages(config, List.of());
            long estimatedInput = attempt.estimatedInputTokens();
            long schemas = estimatedInput - system - user - toolResults - other - framing;
            log.info("agent_model_request_components attemptId={} estimatedInput={} system={} user={} toolResults={} toolSchemas={} other={} framing={}",
                    attemptId, estimatedInput, system, user, toolResults, schemas, other, framing);
            if (!resultTokensByTool.isEmpty() || !argumentTokensByTool.isEmpty()) {
                log.info("agent_model_request_tool_payloads attemptId={} assistantText={} resultTokensByTool={} argumentTokensByTool={}",
                        attemptId, assistantText, resultTokensByTool, argumentTokensByTool);
            }
        } catch (RuntimeException failure) {
            // 诊断失败不触发重试，也不输出可能含正文的异常消息。
            log.warn("模型请求组成估算失败: attemptId={}", attemptId);
        }
    }

    private String toolPayloadCategory(String toolName) {
        return switch (toolName) {
            case "sys_read", "sys_write", "sys_edit", "sys_bash" -> toolName;
            case null, default -> "other";
        };
    }

    private void recordAttemptTrace(AttemptTrace trace, String attemptId, LangChain4jResolution resolution, LocalDateTime startedAt,
                                    long durationMs, ChatResponse response, AgentExecutionBudget.AttemptUsage usage) {
        String status = hasUsableResponse(response) ? "SUCCESS" : "FAILED";
        var reported = response == null ? null : response.tokenUsage();
        Integer cached = null;
        Integer cacheWrite = null;
        Integer reasoning = null;
        if (reported instanceof OpenAiTokenUsage openAi) {
            var input = openAi.inputTokensDetails();
            var output = openAi.outputTokensDetails();
            if (input != null) {
                cached = input.cachedTokens();
                cacheWrite = input.cacheWriteTokens();
            }
            if (output != null) reasoning = output.reasoningTokens();
        } else if (reported instanceof AnthropicTokenUsage anthropic) {
            cached = anthropic.cacheReadInputTokens();
            cacheWrite = anthropic.cacheCreationInputTokens();
        }
        String model = providerOf(resolution) + ":" + modelIdOf(resolution);
        String providerResponseId = response == null || response.metadata() == null ? null : response.metadata().id();
        var metadata = new ModelAttemptMetadata(trace.round(), trace.phase(), trace.attempt(), providerResponseId,
                usage.inputEstimated(), usage.outputEstimated(), cached, cacheWrite, reasoning);
        var config = resolution.config();
        BigDecimal cost = config == null || config.getInputPrice() == null || config.getOutputPrice() == null
                || usage.inputEstimated() || usage.outputEstimated() || (cacheWrite != null && cacheWrite > 0)
                || (cached != null && cached > 0) ? null : calculateCost(resolution, usage.input(), usage.output());
        log.info("agent_model_usage executionId={} attemptId={} round={} phase={} attempt={} model={} responseId={} status={} elapsedMs={} input={} output={} inputEstimated={} outputEstimated={} cachedInput={} cacheWrite={} reasoning={}",
                trace.executionId(), attemptId, trace.round(), trace.phase(), trace.attempt(), model, providerResponseId,
                status, durationMs, usage.input(), usage.output(), usage.inputEstimated(), usage.outputEstimated(),
                cached, cacheWrite, reasoning);
        try {
            agentTraceService.recordModelAttempt(trace.executionId(), trace.tenantId(), trace.agentId(), attemptId,
                    model, startedAt, durationMs, usage.input(), usage.output(), cost, status, metadata);
        } catch (RuntimeException failure) {
            // 计量写入异常不得引起同一模型请求重放。
            log.warn("模型追踪写入失败: executionId={}, attemptId={}", trace.executionId(), attemptId, failure);
        }
    }

    private record AttemptTrace(String executionId, String agentId, String tenantId, int round, String phase, int attempt) {}

    private Semaphore modelSemaphore(AgentExecutionBudget budget) {
        return budget != null && budget.evaluation() ? EVAL_MODEL_CALL_SEMAPHORE : MODEL_CALL_SEMAPHORE;
    }

    private boolean acquirePermit(AgentExecutionBudget budget) throws InterruptedException {
        if (budget == null) {
            return modelSemaphore(budget).tryAcquire(30, TimeUnit.SECONDS);
        }
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long waitMillis;
        while ((waitMillis = waitSliceMillis(waitUntil, budget)) > 0) {
            if (modelSemaphore(budget).tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
        }
        return false;
    }

    private ChatResponse awaitResponse(Future<ChatResponse> future, long timeoutMillis, AgentExecutionBudget budget)
            throws InterruptedException, ExecutionException, TimeoutException {
        if (budget == null) {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }
        long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long waitMillis;
        while ((waitMillis = waitSliceMillis(waitUntil, budget)) > 0) {
            try {
                return future.get(waitMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // 轮询执行级取消与剩余时间。
            }
        }
        throw new TimeoutException();
    }

    private void backoff(long backoffMillis, AgentExecutionBudget budget) throws InterruptedException {
        if (budget == null) {
            Thread.sleep(backoffMillis);
            return;
        }
        long waitUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMillis);
        long waitMillis;
        while ((waitMillis = waitSliceMillis(waitUntil, budget)) > 0) {
            Thread.sleep(waitMillis);
        }
    }

    private long waitSliceMillis(long waitUntil, AgentExecutionBudget budget) {
        // 已发出的模型请求已预占额度，等待时只检查取消与截止时间。
        budget.checkTimeAndCancellation();
        long remaining = waitUntil - System.nanoTime();
        if (remaining <= 0) {
            return 0;
        }
        return Math.min(budget.remainingMillis(),
                Math.min(CANCELLATION_POLL_MILLIS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
    }

    /**
     * 解析模型配置中的超时毫秒数
     */
    public long resolveTimeoutMillis(LangChain4jResolution resolution, long defaultMillis) {
        if (resolution == null || resolution.config() == null) {
            return defaultMillis;
        }
        Integer timeoutSeconds = resolution.config().getTimeoutSeconds();
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return defaultMillis;
        }
        return TimeUnit.SECONDS.toMillis(timeoutSeconds.longValue());
    }

    private long resolveChainTimeoutMillis(LangChain4jResolution resolution,
                                           AgentEngineParams params,
                                           boolean isLastCandidate) {
        long timeoutMillis = resolveTimeoutMillis(resolution, params.getModelCallTimeoutMillis());
        if (isLastCandidate) {
            return timeoutMillis;
        }
        return Math.min(timeoutMillis, FALLBACK_CANDIDATE_TIMEOUT_CAP_MILLIS);
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    /**
     * 判断异常是否可重试
     */
    public boolean isRetryable(Exception e) {
        if (e instanceof RetriableException) return true;
        if (e instanceof NonRetriableException) return false;
        String msg = resolveExMsg(e);
        if (!StringUtils.hasText(msg)) return false;
        String normalized = msg.toLowerCase();
        return containsAny(normalized,
                "api_error",
                "eofexception",
                "socketexception",
                "connection reset",
                "socket closed",
                "unknown error (1000)",
                "timeout",
                "超时",
                "temporar",
                "临时",
                "rate limit",
                "rate_limit",
                "速率限制",
                "限流",
                "429",
                "1302");
    }

    void clearTemporaryUnavailableModels() {
        TEMP_UNAVAILABLE_UNTIL.invalidateAll();
    }

    private void appendFallbackLog(String executionId,
                                   String agentId,
                                   String tenantId,
                                   int round,
                                   long startMs,
                                   AgentLogService agentLogService,
                                   String message) {
        if (agentLogService == null) {
            return;
        }
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN",
                AgentLoopLogTypeEnum.MODEL_FALLBACK_SWITCH.getCode(), round, null,
                message, null, elapsed(startMs));
    }

    private void markTemporaryUnavailable(CooldownKey key, Exception error) {
        if (key == null) {
            return;
        }
        long cooldownMillis = resolveCooldownMillis(error);
        if (cooldownMillis > 0) {
            TEMP_UNAVAILABLE_UNTIL.asMap().merge(key, System.currentTimeMillis() + cooldownMillis, Math::max);
        }
    }

    private long remainingUnavailableMillis(CooldownKey key) {
        Long unavailableUntil = key == null ? null : TEMP_UNAVAILABLE_UNTIL.getIfPresent(key);
        if (unavailableUntil == null) {
            return 0L;
        }
        long remaining = unavailableUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            TEMP_UNAVAILABLE_UNTIL.asMap().remove(key, unavailableUntil);
            return 0L;
        }
        return remaining;
    }

    private long resolveCooldownMillis(Exception error) {
        if (error instanceof AuthenticationException) {
            return TEMP_UNAVAILABLE_ON_FATAL_MILLIS;
        }
        if (error instanceof TimeoutException || error instanceof RateLimitException) {
            return TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS;
        }
        Throwable cause = error instanceof InvalidRequestException ? error.getCause() : error;
        if (cause instanceof HttpException httpError) {
            return switch (httpError.statusCode()) {
                case 401, 402, 403 -> TEMP_UNAVAILABLE_ON_FATAL_MILLIS;
                case 408, 429 -> TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS;
                default -> 0L;
            };
        }
        String message = resolveExMsg(error).toLowerCase(Locale.ROOT);
        // 空响应与非法载荷只影响当前请求，后续请求仍可使用原通道。
        if (error instanceof EmptyResponseException || error instanceof InvalidRequestException
                || containsAny(message, "invalid_request_error", "empty response", "空响应")) {
            return 0L;
        }
        if (containsAny(message, "accountoverdue", "forbidden", "unauthorized", "authentication",
                "invalid api key", "未授权", "鉴权", "认证")) {
            return TEMP_UNAVAILABLE_ON_FATAL_MILLIS;
        }
        if (containsAny(message, "timeout", "超时", "rate limit", "rate_limit", "速率限制", "限流", "\"1302\"")) {
            return TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS;
        }
        return 0L;
    }

    private void validateResponse(ChatResponse response) {
        if (response == null || response.aiMessage() == null) {
            throw new EmptyResponseException("模型返回空响应");
        }
        if (!hasUsableResponse(response)) {
            throw new EmptyResponseException("模型返回空响应，且未提供可执行工具请求");
        }
    }

    private boolean hasUsableResponse(ChatResponse response) {
        AiMessage message = response == null ? null : response.aiMessage();
        return message != null && (message.hasToolExecutionRequests() || StringUtils.hasText(message.text()));
    }

    static final class EmptyResponseException extends IllegalStateException {
        private EmptyResponseException(String message) {
            super(message);
        }
    }

    private boolean containsAny(String message, String... keywords) {
        if (!StringUtils.hasText(message) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (StringUtils.hasText(keyword) && message.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private CooldownKey cooldownKey(String tenantId, LangChain4jResolution resolution) {
        var config = resolution == null ? null : resolution.config();
        if (config == null || !StringUtils.hasText(config.getModelId())) {
            return null;
        }
        // 凭据轮换后使用新的通道键，冷却缓存仅保留凭据指纹。
        String credential = config.getApiKey();
        String credentialHash = StringUtils.hasText(credential)
                ? HashUtils.sha256Hex(credential.getBytes(StandardCharsets.UTF_8)) : null;
        return new CooldownKey(tenantId, config.getConfigId(), config.getProvider(), config.getModelId(),
                config.getProtocol(), config.getBaseUrl(), credentialHash);
    }

    private record CooldownKey(String tenantId, String configId, String provider, String modelId,
                               String protocol, String baseUrl, String credentialHash) {}

    private String providerOf(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null || !StringUtils.hasText(resolution.config().getProvider())) {
            return "unknown";
        }
        return resolution.config().getProvider();
    }

    private String modelIdOf(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null || !StringUtils.hasText(resolution.config().getModelId())) {
            return "unknown";
        }
        return resolution.config().getModelId();
    }

    private String configIdOf(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null || !StringUtils.hasText(resolution.config().getConfigId())) {
            return modelIdOf(resolution);
        }
        return resolution.config().getConfigId();
    }

    private String resolveExMsg(Exception e) {
        return e != null && StringUtils.hasText(e.getMessage()) ? e.getMessage()
                : e != null ? e.getClass().getSimpleName() : "unknown";
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private int resolveMaxRetries(LangChain4jResolution resolution, AgentEngineParams params) {
        int paramRetries = params != null ? Math.max(params.getMaxModelRetries(), 0) : 0;
        int modelRetries = resolution != null && resolution.config() != null
                ? Math.max(resolution.config().getRetryCount(), 0)
                : 0;
        return Math.max(2, Math.max(paramRetries, modelRetries));
    }

    private long resolveRetryIntervalMillis(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null) {
            return 1000L;
        }
        int retryIntervalSeconds = Math.max(resolution.config().getRetryIntervalSeconds(), 1);
        return TimeUnit.SECONDS.toMillis(retryIntervalSeconds);
    }

    private long extractInputTokens(ChatResponse response) {
        var usage = response == null ? null : response.tokenUsage();
        return usage == null || usage.inputTokenCount() == null ? 0L : usage.inputTokenCount();
    }

    private long extractOutputTokens(ChatResponse response) {
        var usage = response == null ? null : response.tokenUsage();
        return usage == null || usage.outputTokenCount() == null ? 0L : usage.outputTokenCount();
    }

    private BigDecimal calculateCost(LangChain4jResolution resolution, long inputTokens, long outputTokens) {
        if (resolution == null || resolution.config() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal inputPrice = resolution.config().getInputPrice() == null ? BigDecimal.ZERO : resolution.config().getInputPrice();
        BigDecimal outputPrice = resolution.config().getOutputPrice() == null ? BigDecimal.ZERO : resolution.config().getOutputPrice();
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1000), 6, java.math.RoundingMode.HALF_UP);
    }

    /** 模型调用结果封装 */
    public record ModelCallResult(ChatResponse response, LangChain4jResolution resolution) {}
}
