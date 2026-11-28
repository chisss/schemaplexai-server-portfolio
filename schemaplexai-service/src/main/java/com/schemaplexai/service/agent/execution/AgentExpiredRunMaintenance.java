package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.AgentExecutionEventTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
class AgentExpiredRunMaintenance {
    static final String FAILURE_REASON = "执行节点心跳超期，需核对已完成的操作后重新发起任务";
    private final JdbcTemplate jdbc;
    private final AgentTerminalEventOutbox outbox;
    private final ExecutionStopSignal stopSignal;

    @Scheduled(fixedDelayString = "${schemaplexai.agent.recovery.sweep-interval-ms:30000}", initialDelay = 60_000)
    @Transactional
    public void failExpiredRuns() {
        var failed = jdbc.query("""
                WITH expired AS MATERIALIZED (
                    SELECT id, tenant_id FROM sf_agent_execution
                     WHERE parent_execution_id IS NULL AND deleted = 0 AND status = 'running'
                       AND ((harness_owner IS NOT NULL
                             AND harness_lease_until < clock_timestamp() - INTERVAL '10 minutes')
                            OR (harness_owner IS NULL AND runtime_engine IN ('solo_langchain4j', 'team_langgraph4j')
                                AND GREATEST(started_at, created_at) < clock_timestamp() - INTERVAL '2 hours'))
                     ORDER BY COALESCE(harness_lease_until, started_at, created_at)
                     LIMIT 100 FOR UPDATE SKIP LOCKED
                )
                UPDATE sf_agent_execution run
                   SET status = 'failed', completed_at = clock_timestamp(), error_message = ?
                  FROM expired
                 WHERE run.tenant_id = expired.tenant_id AND run.deleted = 0
                   AND (run.id = expired.id OR run.parent_execution_id = expired.id::text)
                   AND run.status IN ('running', 'queued', 'paused')
                RETURNING run.id
                """, (row, index) -> AgentExecutionEvent.builder()
                .eventType(AgentExecutionEventTypeEnum.FAILED.getCode())
                .executionId(row.getString(1))
                .message(FAILURE_REASON).timestamp(Instant.now()).build(), FAILURE_REASON);
        failed.forEach(event -> {
            outbox.enqueue(event);
            stopSignal.publishAfterCommit(event.getExecutionId());
        });
        if (!failed.isEmpty()) log.warn("已结束心跳超期的 Agent 执行: count={}", failed.size());
    }
}
