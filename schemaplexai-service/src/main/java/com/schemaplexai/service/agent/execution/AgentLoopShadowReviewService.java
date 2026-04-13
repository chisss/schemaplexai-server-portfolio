package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Agentic Loop 影子质量审核服务
 *
 * <p>将高延迟的结构化质量检查放到后台执行，只在安全点做限时等待。</p>
 */
@Component
public class AgentLoopShadowReviewService {

    private final AgentLoopQualityChecker qualityChecker;
    private final Executor agentExecutor;

    public AgentLoopShadowReviewService(AgentLoopQualityChecker qualityChecker,
                                        @Qualifier("agentExecutorPool") Executor agentExecutor) {
        this.qualityChecker = qualityChecker;
        this.agentExecutor = agentExecutor;
    }

    /**
     * 提交结构化影子审核任务
     */
    public CompletableFuture<QualityReflectionFeedback> submitStructuralReview(AgentExecutionContext ctx,
                                                                                String content,
                                                                                int qualityReflectionCount,
                                                                                AgentEngineParams params) {
        if (params == null
                || !params.isShadowQualityReviewEnabled()
                || !StringUtils.hasText(content)
                || qualityReflectionCount >= params.getMaxQualityReflections()) {
            return null;
        }
        return CompletableFuture.supplyAsync(
                () -> qualityChecker.buildStructuralFeedback(ctx, content, qualityReflectionCount, params),
                agentExecutor
        );
    }

    /**
     * 限时等待影子审核结果。超时返回 null，不视为失败。
     */
    public QualityReflectionFeedback await(CompletableFuture<QualityReflectionFeedback> future, long awaitMillis) {
        if (future == null) {
            return null;
        }
        long safeAwaitMillis = Math.max(awaitMillis, 0L);
        try {
            return future.get(safeAwaitMillis, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 非阻塞消费已完成的审核结果。
     */
    public QualityReflectionFeedback consumeIfDone(CompletableFuture<QualityReflectionFeedback> future) {
        if (future == null || !future.isDone()) {
            return null;
        }
        return future.getNow(null);
    }
}
