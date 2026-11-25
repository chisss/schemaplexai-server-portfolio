package com.schemaplexai.service.agent.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Agent 执行事件（SSE）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionEvent {

    /** QUEUED/ROUND_START/AI_RESPONSE/TOOL_CALL/TOOL_RESULT/REQUIRE_INPUT/USER_INPUT/RESUMED/COMPLETED/FAILED/CANCELLED */
    private String eventType;

    private String executionId;

    /** 终态事件的稳定投递 ID。 */
    private String eventId;

    private Integer roundNum;

    /** 用户可读描述 */
    private String message;

    private Long elapsedMs;

    /** 统一执行状态，供前端状态卡直接渲染 */
    private String executionState;

    /** 链路追踪 ID */
    private String traceId;

    /** 是否允许用户重试 */
    private Boolean retryable;

    /** 关联治理页面或明细页 */
    private String targetUrl;

    /** 证据摘要列表 */
    private List<String> evidence;

    /** 附加数据（如 finalOutput） */
    private Object payload;

    private Instant timestamp;
}
