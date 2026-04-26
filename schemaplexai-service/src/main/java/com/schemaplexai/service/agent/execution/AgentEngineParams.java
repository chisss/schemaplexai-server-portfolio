package com.schemaplexai.service.agent.execution;

import lombok.Builder;
import lombok.Getter;

/**
 * Agent 执行引擎运行参数
 * <p>由 {@link AgentEngineConfigLoader} 从数据库加载，替代原来的硬编码常量</p>
 */
@Getter
@Builder
public class AgentEngineParams {

    /** 最大推理轮次 */
    private final int maxRounds;

    /** 每轮最大工具调用数 */
    private final int maxToolCallsPerRound;

    /** 短期记忆最大消息数 */
    private final int maxMessages;

    /** 模型调用最大重试次数 */
    private final int maxModelRetries;

    /** 最大质量反思轮次 */
    private final int maxQualityReflections;

    /** 是否启用影子质量审核 */
    private final boolean shadowQualityReviewEnabled;

    /** 影子质量审核单次等待时长（毫秒） */
    private final long shadowQualityReviewAwaitMillis;

    /** 非预期工具调用最大恢复次数 */
    private final int maxUnexpectedToolCallRecoveries;

    /** 工具结果消息最大长度 */
    private final int maxToolResultMessageLength;

    /** 模型调用超时毫秒数 */
    private final long modelCallTimeoutMillis;

    /** 降级收敛证据条数上限 */
    private final int fallbackEvidenceLimit;

    /** 降级收敛单条证据文本长度上限 */
    private final int fallbackTextLimit;

    /** 日志内容截断长度 */
    private final int logContentLimit;

    /** 工具请求摘要截断长度 */
    private final int toolRequestSummaryLimit;

    /** 是否启用并行工具执行（读工具并行，写工具串行） */
    @Builder.Default
    private final boolean parallelToolExecution = true;

    /** 并行工具执行最大并发数 */
    @Builder.Default
    private final int maxParallelTools = 4;

    /** 循环检测 WARNING 累积触发强制收敛的阈值 */
    @Builder.Default
    private final int maxLoopWarnings = 5;
}
