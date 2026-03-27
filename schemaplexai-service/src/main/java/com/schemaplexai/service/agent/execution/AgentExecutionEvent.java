package com.schemaplexai.service.agent.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Agent 执行事件（SSE）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionEvent {

    /** QUEUED/ROUND_START/AI_RESPONSE/TOOL_CALL/TOOL_RESULT/REQUIRE_INPUT/COMPLETED/FAILED/CANCELLED */
    private String eventType;

    private String executionId;

    private Integer roundNum;

    /** 用户可读描述 */
    private String message;

    private Long elapsedMs;

    /** 附加数据（如 finalOutput） */
    private Object payload;

    private Instant timestamp;
}
