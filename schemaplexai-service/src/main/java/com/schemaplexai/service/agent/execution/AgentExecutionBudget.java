package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** 单次逻辑执行的时间、用量与取消边界。用量由编排线程累加。 */
record AgentExecutionBudget(long deadlineMs, long maxTokens, long previousElapsedMillis, long segmentStartedAt,
                            AgentLoopState state, BooleanSupplier stopped, TeamExecutionBudget.Account teamBudget) {

    AgentExecutionBudget(long deadlineMs, long maxTokens, long previousElapsedMillis, long segmentStartedAt,
                         AgentLoopState state, BooleanSupplier stopped) {
        this(deadlineMs, maxTokens, previousElapsedMillis, segmentStartedAt, state, stopped, null);
    }

    long elapsedMillis() {
        return previousElapsedMillis + Math.max(0L, System.currentTimeMillis() - segmentStartedAt);
    }

    long remainingMillis() {
        return Math.max(0L, deadlineMs - System.currentTimeMillis());
    }

    long remainingTokens() {
        long local = localRemainingTokens();
        return teamBudget == null ? local : Math.min(local, teamBudget.remaining());
    }

    long localRemainingTokens() {
        return maxTokens - state.getTotalTokenInput() - state.getTotalTokenOutput();
    }

    boolean canDispatch() {
        return remainingMillis() > 0 && remainingTokens() > 0 && !stopped.getAsBoolean();
    }

    void checkActive() {
        checkTimeAndCancellation();
        if (remainingTokens() <= 0) {
            throw new BudgetExceededException("累计 Token 预算已用尽");
        }
    }

    void checkTimeAndCancellation() {
        if (stopped.getAsBoolean()) {
            throw new CancellationException("用户已停止执行");
        }
        if (remainingMillis() == 0) {
            throw new BudgetExceededException("执行截止时间已过");
        }
    }

    ModelAttempt prepare(AiModelConfig config, ChatRequest request, AgentModelRequestPlanner planner) {
        checkActive();
        long inputTokens = planner.estimateInput(config, request.messages(), request.toolSpecifications());
        long availableOutput = remainingTokens() - inputTokens;
        if (availableOutput <= 0) {
            throw new BudgetExceededException("剩余 Token 预算不足以发送模型请求");
        }
        int outputCap = (int) Math.min(availableOutput, Integer.MAX_VALUE);
        if (config != null && config.getMaxTokens() > 0) {
            outputCap = Math.min(outputCap, config.getMaxTokens());
        }
        if (request.maxOutputTokens() != null && request.maxOutputTokens() > 0) {
            outputCap = Math.min(outputCap, request.maxOutputTokens());
        }
        long reserved = 0;
        if (teamBudget != null) {
            reserved = teamBudget.reserve(inputTokens, inputTokens + outputCap);
            if (reserved <= inputTokens) {
                throw new BudgetExceededException("Team 共享 Token 预算不足以发送模型请求");
            }
            outputCap = (int) (reserved - inputTokens);
        }
        return new ModelAttempt(request.toBuilder().maxOutputTokens(outputCap).build(), inputTokens, reserved);
    }

    AttemptUsage recordAttempt(AiModelConfig config, ModelAttempt attempt, ChatResponse response,
                               TokenEstimatorSupport estimator) {
        TokenUsage usage = response == null || response.metadata() == null ? null : response.metadata().tokenUsage();
        boolean inputEstimated = usage == null || usage.inputTokenCount() == null;
        boolean outputEstimated = usage == null || usage.outputTokenCount() == null;
        long input = inputEstimated ? attempt.estimatedInputTokens() : usage.inputTokenCount();
        long output = outputEstimated ? estimator.estimateMessage(config, response == null ? null : response.aiMessage())
                : usage.outputTokenCount();
        var result = new AttemptUsage(Math.max(0L, input), Math.max(0L, output), inputEstimated, outputEstimated);
        state.addTokenUsage(result.input(), result.output());
        if (teamBudget != null) {
            // 对于没有完整回执的请求保留全部预占，防止供应商仍在执行时重复分配。
            long charged = response == null ? attempt.reservedTokens() : result.input() + result.output();
            teamBudget.settle(attempt.reservedTokens(), charged);
        }
        return result;
    }

    record ModelAttempt(ChatRequest request, long estimatedInputTokens, long reservedTokens) {
    }

    record AttemptUsage(long input, long output, boolean inputEstimated, boolean outputEstimated) {
    }

    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException(String message) {
            super(message);
        }
    }
}
