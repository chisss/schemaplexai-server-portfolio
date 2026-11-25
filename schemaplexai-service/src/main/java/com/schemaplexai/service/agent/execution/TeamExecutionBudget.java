package com.schemaplexai.service.agent.execution;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import java.util.List;

/** Team 根执行持有共享余额，成员在模型派发前预占，并发成员通过行锁排序。 */
@Component
@RequiredArgsConstructor
public class TeamExecutionBudget {

    static final String FREEZE_ON_PAUSE = """
            team_active_remaining_ms = CASE WHEN team_deadline_ms IS NULL THEN team_active_remaining_ms
              ELSE GREATEST(0, team_deadline_ms - (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint) END,
            team_deadline_ms = NULL
            """;

    private final JdbcTemplate jdbc;

    public Account open(String tenantId, String rootId, SandboxPolicy policy) {
        long tokens = policy == null || policy.getMaxTokenBudget() <= 0 ? 200_000L : policy.getMaxTokenBudget();
        int minutes = policy == null || policy.getMaxExecutionMinutes() <= 0 ? 30 : policy.getMaxExecutionMinutes();
        return open(tenantId, rootId, tokens, System.currentTimeMillis() + minutes * 60_000L,
                Math.min(tokens / 5, 8_192L));
    }

    Account open(String tenantId, String rootId, long maxTokens, long deadline, long finalReserve) {
        List<Long> deadlines = jdbc.query("""
                UPDATE sf_agent_execution
                   SET team_tokens_remaining = COALESCE(team_tokens_remaining, ?),
                       team_deadline_ms = COALESCE(team_deadline_ms, CASE WHEN team_active_remaining_ms IS NULL THEN ?
                           ELSE (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint + team_active_remaining_ms END),
                       team_final_reserve = COALESCE(team_final_reserve, ?)
                 WHERE tenant_id = ?::uuid AND id = ?::uuid AND parent_execution_id IS NULL
                   AND deleted = 0 AND status = 'running'
                RETURNING team_deadline_ms
                """, (row, index) -> row.getLong(1), maxTokens, deadline, finalReserve, tenantId, rootId);
        if (deadlines.isEmpty()) {
            throw new IllegalStateException("无法初始化 Team 根执行预算");
        }
        return new Account(tenantId, rootId, deadlines.getFirst(), false);
    }

    public final class Account {
        private final String tenantId;
        private final String rootId;
        private final long deadlineMs;
        private final boolean finalization;

        private Account(String tenantId, String rootId, long deadlineMs, boolean finalization) {
            this.tenantId = tenantId;
            this.rootId = rootId;
            this.deadlineMs = deadlineMs;
            this.finalization = finalization;
        }

        public Account forFinalization() {
            return new Account(tenantId, rootId, deadlineMs, true);
        }

        public boolean canStartFinalization() {
            return System.currentTimeMillis() < deadlineMs && forFinalization().remaining() > 0;
        }

        long deadlineMs() {
            return deadlineMs;
        }

        long remaining() {
            return jdbc.query("""
                    SELECT GREATEST(0, team_tokens_remaining - CASE WHEN ? THEN 0 ELSE team_final_reserve END)
                      FROM sf_agent_execution
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND deleted = 0 AND status = 'running'
                    """, (row, index) -> row.getLong(1), finalization, tenantId, rootId).stream().findFirst().orElse(0L);
        }

        long reserve(long input, long requested) {
            return jdbc.query("""
                    WITH allowance AS MATERIALIZED (
                        SELECT LEAST(team_tokens_remaining - CASE WHEN ? THEN 0 ELSE team_final_reserve END, ?) AS amount
                          FROM sf_agent_execution
                         WHERE tenant_id = ?::uuid AND id = ?::uuid AND deleted = 0 AND status = 'running'
                           AND team_tokens_remaining - CASE WHEN ? THEN 0 ELSE team_final_reserve END > ?
                           AND team_deadline_ms > (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
                         FOR UPDATE
                    )
                    UPDATE sf_agent_execution
                       SET team_tokens_remaining = team_tokens_remaining - allowance.amount
                      FROM allowance
                     WHERE tenant_id = ?::uuid AND id = ?::uuid
                    RETURNING allowance.amount
                    """, (row, index) -> row.getLong(1), finalization, requested, tenantId, rootId, finalization, input,
                    tenantId, rootId).stream().findFirst().orElse(0L);
        }

        void settle(long reserved, long actual) {
            if (reserved == actual) {
                return;
            }
            jdbc.update("""
                    UPDATE sf_agent_execution
                       SET team_tokens_remaining = team_tokens_remaining + ?
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND deleted = 0
                    """, reserved - actual, tenantId, rootId);
        }
    }
}
