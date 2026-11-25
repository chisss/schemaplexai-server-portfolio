package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AgentTerminalEventOutbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /** 与调用方受租户约束的状态更新使用同一事务。 */
    public void enqueue(AgentExecutionEvent event) {
        event.setEventId(event.getExecutionId() + ":terminal");
        try {
            jdbc.update("""
                    INSERT INTO sf_agent_terminal_event (execution_id, tenant_id, payload)
                    SELECT id, tenant_id, ? FROM sf_agent_execution WHERE id = ?::uuid AND deleted = 0
                    ON CONFLICT (execution_id) DO NOTHING
                    """, json.writeValueAsString(event), event.getExecutionId());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("终态事件无法序列化", e);
        }
    }

    Optional<AgentExecutionEvent> find(String tenantId, String executionId) {
        return jdbc.query("""
                SELECT payload FROM sf_agent_terminal_event
                 WHERE tenant_id = ?::uuid AND execution_id = ?::uuid
                """, (row, index) -> decode(row.getString(1)), tenantId, executionId).stream().findFirst();
    }

    List<Delivery> claim(int limit) {
        String claimId = UUID.randomUUID().toString();
        return jdbc.query("""
                WITH next_events AS MATERIALIZED (
                    SELECT execution_id FROM sf_agent_terminal_event
                     WHERE published_at IS NULL AND next_attempt_at <= clock_timestamp()
                     ORDER BY next_attempt_at, execution_id LIMIT ? FOR UPDATE SKIP LOCKED
                )
                UPDATE sf_agent_terminal_event
                   SET claim_id = ?::uuid, next_attempt_at = clock_timestamp() + interval '60 seconds'
                 WHERE execution_id IN (SELECT execution_id FROM next_events)
                RETURNING execution_id, payload
                """, (row, index) -> new Delivery(row.getString(1), claimId, row.getString(2)), limit, claimId);
    }

    void confirm(Delivery delivery) {
        jdbc.update("""
                UPDATE sf_agent_terminal_event SET published_at = clock_timestamp()
                 WHERE execution_id = ?::uuid AND claim_id = ?::uuid AND published_at IS NULL
                """, delivery.executionId(), delivery.claimId());
    }

    void retry(Delivery delivery) {
        jdbc.update("""
                UPDATE sf_agent_terminal_event SET claim_id = NULL, next_attempt_at = clock_timestamp() + interval '15 seconds'
                 WHERE execution_id = ?::uuid AND claim_id = ?::uuid AND published_at IS NULL
                """, delivery.executionId(), delivery.claimId());
    }

    AgentExecutionEvent decode(String payload) {
        try {
            return json.readValue(payload, AgentExecutionEvent.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("终态事件无法反序列化", e);
        }
    }

    record Delivery(String executionId, String claimId, String payload) {}
}
