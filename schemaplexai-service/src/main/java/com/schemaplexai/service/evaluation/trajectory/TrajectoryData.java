package com.schemaplexai.service.evaluation.trajectory;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 结构化 Agent 执行轨迹（评估中心视角）
 * <p>
 * 由 {@link TrajectoryCollector} 从三层数据组装：
 * sf_chat_message（对话轨迹）+ sf_agent_execution_log（关键事件）+ sf_agent_trace_span（span 树摘要）。
 * 是轨迹评估的裁判输入与结果回放数据源。
 * </p>
 */
@Data
@Builder
public class TrajectoryData {

    /** 执行 ID */
    private String executionId;

    /** 会话 ID */
    private String conversationId;

    /** Agent ID */
    private String agentId;

    /** 执行状态（AgentExecution.status） */
    private String executionStatus;

    /** 执行轮次 */
    private Integer rounds;

    /** 总输入 Token */
    private Long tokenInput;

    /** 总输出 Token */
    private Long tokenOutput;

    /** 总耗时（毫秒） */
    private Long durationMs;

    /** 累计成本 */
    private BigDecimal cost;

    /** 有序对话消息（含工具入参/返回） */
    private List<TrajectoryMessage> messages;

    /** 有序关键执行事件 */
    private List<TrajectoryEvent> events;

    /** span 统计摘要 */
    private SpanSummary spanSummary;

    /**
     * 单条轨迹消息（对应 sf_chat_message 一行）
     */
    @Data
    @Builder
    public static class TrajectoryMessage {

        /** SYSTEM / USER / AI / TOOL_EXECUTION_RESULT */
        private String messageType;

        /** 消息文本（AI 思考/回答、用户问题、工具返回等） */
        private String text;

        /** 工具名（TOOL_EXECUTION_RESULT 消息） */
        private String toolName;

        /** 工具调用 ID（TOOL_EXECUTION_RESULT 与 AI tool_call 关联） */
        private String toolCallId;

        /** AI 消息中的工具调用请求 JSON（含入参） */
        private String toolCallsJson;

        /** 会话内顺序 */
        private Integer messageIndex;

        /** 对话轮次索引 */
        private Integer turnIndex;
    }

    /**
     * 关键执行事件（对应 sf_agent_execution_log 一行）
     */
    @Data
    @Builder
    public static class TrajectoryEvent {

        /** AgentLoopLogTypeEnum 事件类型（ROUND_START/TOOL_CALL_LIMITED/LOOP_WARNING 等） */
        private String logType;

        /** 轮次编号 */
        private Integer roundNum;

        /** 工具名（工具相关事件） */
        private String toolName;

        /** 事件内容 */
        private String content;

        /** 距执行开始的累计耗时（毫秒） */
        private Integer elapsedMs;
    }

    /**
     * span 统计摘要（对应 sf_agent_trace_span 聚合）
     */
    @Data
    @Builder
    public static class SpanSummary {

        /** span 总数 */
        private int total;

        /** 失败 span 数 */
        private int failed;

        /** 可恢复失败数 */
        private int recoverable;

        /** 失败归因类别（去重，如 MODEL_FAILURE/TOOL_FAILURE） */
        private List<String> failureCategories;

        /** span 成本合计 */
        private BigDecimal cost;
    }
}
