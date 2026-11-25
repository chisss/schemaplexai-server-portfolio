package com.schemaplexai.service.agent.execution;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Agentic Loop 运行时状态持有类
 * <p>封装单次执行循环中需要跨轮次共享的可变状态，避免在 runAgenticLoop 中堆积大量局部变量</p>
 */
@Data
public class AgentLoopState {

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private AgentExecutionBudget budget;

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

    /** 循环退出原因与基于已收集证据的降级标记 */
    private AgentLoopEndReason endReason;
    private boolean degraded;
    private AgentAcceptance.Status acceptanceStatus;

    /** 质量反思针对的上一版正文，用于抑制同文重试。 */
    private String lastReflectedContent;

    @ToString.Exclude
    private String securityCheckedContent;

    /** 当前轮次是否处于质量反思待处理状态 */
    private boolean qualityReflectionPending;

    /** 待消费的影子质量审核任务 */
    private CompletableFuture<AgentLoopCompletionHandler.QualityReflectionFeedback> pendingShadowReview;

    /** 非预期工具调用恢复次数 */
    private int unexpectedToolCallRecoveries;

    // ---- 循环检测字段（AgentLoopDetectionService 使用）----

    /** 最近 N 轮 AI 响应文本的 SHA-256 哈希（滑动窗口） */
    private final Deque<String> recentResponseHashes = new ArrayDeque<>();

    /** 最近 N 轮工具调用序列摘要（滑动窗口） */
    private final Deque<String> recentToolSequences = new ArrayDeque<>();

    /** 循环告警累计次数 */
    private int loopWarningCount;

    /** 当前工作区连续只读期间已确认的文件读取摘要。 */
    @ToString.Exclude
    private final Set<String> desktopReadFingerprints = new HashSet<>();
    private int repeatedDesktopReadRounds;
    private int consecutiveDesktopReadRounds;
    private boolean desktopFileChanged;
    private boolean desktopReadReminderSent;
    private String desktopVerificationReminder;

    /**
     * 累加本轮 Token 用量
     */
    public void addTokenUsage(long inputTokens, long outputTokens) {
        this.totalTokenInput  += inputTokens;
        this.totalTokenOutput += outputTokens;
    }
}
