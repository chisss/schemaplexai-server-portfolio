package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.AgentLoopLogTypeEnum;
import com.schemaplexai.service.ai.LangChain4jResolution;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 模型调用器
 *
 * <p>封装模型调用的超时控制、重试、降级链切换逻辑，从 AgentExecutionEngine 中拆分出来</p>
 */
@Slf4j
@Component
public class AgentModelInvoker {

    private static final ExecutorService MODEL_CALL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final long TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long TEMP_UNAVAILABLE_ON_FATAL_MILLIS = TimeUnit.MINUTES.toMillis(10);
    private static final Map<String, Long> TEMP_UNAVAILABLE_UNTIL = new ConcurrentHashMap<>();

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
        Exception lastError = null;
        boolean attempted = false;
        for (int i = 0; i < modelChain.size(); i++) {
            LangChain4jResolution resolution = modelChain.get(i);
            long remainingUnavailableMillis = remainingUnavailableMillis(resolution);
            if (remainingUnavailableMillis > 0) {
                if (i < modelChain.size() - 1) {
                    appendFallbackLog(executionId, agentId, tenantId, round, startMs, agentLogService,
                            "模型当前处于冷却期，跳过并切换至下一个降级模型: provider=" + providerOf(resolution)
                                    + ", modelId=" + modelIdOf(resolution)
                                    + ", remainingMs=" + remainingUnavailableMillis);
                }
                continue;
            }
            attempted = true;
            try {
                ChatResponse response = invokeWithRetry(resolution, request, params,
                        executionId, agentId, tenantId, round, startMs, agentLogService);
                clearTemporaryUnavailable(resolution);
                return new ModelCallResult(response, resolution);
            } catch (Exception e) {
                lastError = e;
                markTemporaryUnavailable(resolution, e);
                if (i < modelChain.size() - 1) {
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
        Exception lastError = null;
        int maxRetries = resolveMaxRetries(resolution, params);
        int maxAttempts = maxRetries + 1;
        long timeoutMillis = resolveTimeoutMillis(resolution, params.getModelCallTimeoutMillis());
        long retryIntervalMillis = resolveRetryIntervalMillis(resolution);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invokeWithTimeout(resolution, request, timeoutMillis);
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
                    Thread.sleep(retryIntervalMillis * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("AI 调用失败");
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
        Future<ChatResponse> future = MODEL_CALL_EXECUTOR.submit(() -> resolution.model().chat(request));
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            long seconds = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(timeoutMillis));
            throw new TimeoutException("AI 调用超时，超过 " + seconds + " 秒");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new IllegalStateException(cause != null ? cause.getMessage() : "AI 调用失败", cause);
        }
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

    // =========================================================================
    //  辅助方法
    // =========================================================================

    /**
     * 判断异常是否可重试
     */
    public boolean isRetryable(Exception e) {
        String msg = e.getMessage();
        if (!StringUtils.hasText(msg)) return false;
        String normalized = msg.toLowerCase();
        return containsAny(normalized,
                "api_error",
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
        TEMP_UNAVAILABLE_UNTIL.clear();
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

    private void markTemporaryUnavailable(LangChain4jResolution resolution, Exception error) {
        String modelKey = buildModelKey(resolution);
        if (!StringUtils.hasText(modelKey)) {
            return;
        }
        long cooldownMillis = resolveCooldownMillis(error);
        if (cooldownMillis <= 0) {
            return;
        }
        TEMP_UNAVAILABLE_UNTIL.put(modelKey, System.currentTimeMillis() + cooldownMillis);
    }

    private void clearTemporaryUnavailable(LangChain4jResolution resolution) {
        String modelKey = buildModelKey(resolution);
        if (StringUtils.hasText(modelKey)) {
            TEMP_UNAVAILABLE_UNTIL.remove(modelKey);
        }
    }

    private long remainingUnavailableMillis(LangChain4jResolution resolution) {
        String modelKey = buildModelKey(resolution);
        if (!StringUtils.hasText(modelKey)) {
            return 0L;
        }
        Long unavailableUntil = TEMP_UNAVAILABLE_UNTIL.get(modelKey);
        if (unavailableUntil == null) {
            return 0L;
        }
        long remaining = unavailableUntil - System.currentTimeMillis();
        if (remaining <= 0) {
            TEMP_UNAVAILABLE_UNTIL.remove(modelKey, unavailableUntil);
            return 0L;
        }
        return remaining;
    }

    private long resolveCooldownMillis(Exception error) {
        String message = resolveExMsg(error).toLowerCase();
        if (!StringUtils.hasText(message)) {
            return 0L;
        }
        if (containsAny(message,
                "invalid_request_error",
                "暂不支持",
                "不支持",
                "not support",
                "unsupported",
                "accountoverdue",
                "forbidden",
                "unauthorized",
                "authentication",
                "未授权",
                "鉴权",
                "认证")) {
            return TEMP_UNAVAILABLE_ON_FATAL_MILLIS;
        }
        if (containsAny(message,
                "timeout",
                "超时",
                "rate limit",
                "rate_limit",
                "速率限制",
                "限流",
                "429",
                "1302",
                "temporar",
                "临时",
                "暂时")) {
            return TEMP_UNAVAILABLE_ON_TIMEOUT_MILLIS;
        }
        return 0L;
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

    private String buildModelKey(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null || !StringUtils.hasText(resolution.config().getModelId())) {
            return "";
        }
        return providerOf(resolution) + ":" + modelIdOf(resolution);
    }

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
        return Math.max(paramRetries, modelRetries);
    }

    private long resolveRetryIntervalMillis(LangChain4jResolution resolution) {
        if (resolution == null || resolution.config() == null) {
            return 1000L;
        }
        int retryIntervalSeconds = Math.max(resolution.config().getRetryIntervalSeconds(), 1);
        return TimeUnit.SECONDS.toMillis(retryIntervalSeconds);
    }

    /** 模型调用结果封装 */
    public record ModelCallResult(ChatResponse response, LangChain4jResolution resolution) {}
}
