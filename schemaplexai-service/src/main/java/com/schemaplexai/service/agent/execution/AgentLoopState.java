package com.schemaplexai.service.agent.execution;

import lombok.Data;

import java.util.concurrent.CompletableFuture;

/**
 * Agentic Loop 运行时状态持有类
 * <p>封装单次执行循环中需要跨轮次共享的可变状态，避免在 runAgenticLoop 中堆积大量局部变量</p>
 */
@Data
public class AgentLoopState {

    /** 累计输入 Token 数 */
    private long totalTokenInput;

    /** 累计输出 Token 数 */
    private long totalTokenOutput;

    /** 最后一轮 AI 响应文本 */
    private String lastContent = "";

    /** 最后执行的轮次编号 */
    private int lastRound;

    /** 质量反思已执行次数 */
    private int qualityReflectionCount;

    /** 循环是否正常完成（AI 无工具调用直接返回） */
    private boolean loopCompleted;

    /** 是否已触发强制收敛请求 */
    private boolean forceCompletionRequested;

    /** 当前轮次是否处于质量反思待处理状态 */
    private boolean qualityReflectionPending;

    /** 待消费的影子质量审核任务 */
    private CompletableFuture<AgentLoopCompletionHandler.QualityReflectionFeedback> pendingShadowReview;

    /** 影子质量审核对应的候选输出 */
    private String pendingShadowReviewContent;

    /** 非预期工具调用恢复次数 */
    private int unexpectedToolCallRecoveries;

    /**
     * 累加本轮 Token 用量
     */
    public void addTokenUsage(long inputTokens, long outputTokens) {
        this.totalTokenInput  += inputTokens;
        this.totalTokenOutput += outputTokens;
    }
}
