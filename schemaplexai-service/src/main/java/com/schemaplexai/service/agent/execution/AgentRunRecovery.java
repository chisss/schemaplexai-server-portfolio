package com.schemaplexai.service.agent.execution;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Solo 运行租约和普通工具派发账本。所有绕过租户拦截器的查询都显式绑定租户。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRecovery {

    private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();

    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("agent-run-heartbeat").factory());

    public boolean canRecover(String tenantId, String executionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM sf_agent_execution
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND parent_execution_id IS NULL AND deleted = 0
                       AND status = 'running' AND harness_owner IS NOT NULL
                       AND harness_lease_until <= clock_timestamp()
                )
                """, Boolean.class, tenantId, executionId));
    }

    Lease claim(AgentExecutionContext context, String status, boolean recovering) {
        String owner = UUID.randomUUID().toString();
        var rows = jdbc.query("""
                UPDATE sf_agent_execution
                   SET harness_owner = ?::uuid, harness_lease_until = clock_timestamp() + interval '90 seconds',
                       harness_recoverable = CASE WHEN ? THEN harness_recoverable ELSE false END
                 WHERE tenant_id = ?::uuid AND id = ?::uuid AND parent_execution_id IS NULL AND deleted = 0
                   AND status = ? AND (harness_lease_until IS NULL OR harness_lease_until <= clock_timestamp())
                   AND (NOT ? OR harness_owner IS NOT NULL)
                RETURNING COALESCE(harness_round, 0), harness_recoverable
                """, (row, index) -> new Checkpoint(row.getInt(1), row.getBoolean(2)), owner, recovering,
                context.getTenantId(), context.getExecutionId(), status, recovering);
        if (rows.isEmpty()) {
            return null;
        }
        return new Lease(context.getTenantId(), context.getExecutionId(), owner, rows.getFirst());
    }

    static String currentOwner(String executionId) {
        Lease lease = CURRENT.get();
        return lease == null || !executionId.equals(lease.executionId) ? null : lease.owner;
    }

    @PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final String tenantId;
        private final String executionId;
        private final String owner;
        private final Checkpoint checkpoint;
        private final ScheduledFuture<?> renewal;

        private Lease(String tenantId, String executionId, String owner, Checkpoint checkpoint) {
            this.tenantId = tenantId;
            this.executionId = executionId;
            this.owner = owner;
            this.checkpoint = checkpoint;
            this.renewal = heartbeat.scheduleWithFixedDelay(this::renew, 15, 15, TimeUnit.SECONDS);
        }

        void bind() {
            CURRENT.set(this);
        }

        Checkpoint checkpoint() {
            return checkpoint;
        }

        private void renew() {
            try {
                jdbc.update("""
                        UPDATE sf_agent_execution
                           SET harness_lease_until = clock_timestamp() + interval '90 seconds'
                         WHERE tenant_id = ?::uuid AND id = ?::uuid AND harness_owner = ?::uuid
                           AND harness_lease_until > clock_timestamp() AND status IN ('running', 'queued', 'paused')
                        """, tenantId, executionId, owner);
            } catch (RuntimeException e) {
                log.warn("Agent 执行续租失败，executionId={}，errorType={}", executionId, e.getClass().getSimpleName());
            }
        }

        void checkpoint(int round, long input, long output, long elapsed) {
            int updated = jdbc.update("""
                    UPDATE sf_agent_execution
                       SET harness_round = ?, harness_recoverable = true, token_input = ?, token_output = ?,
                           runtime_elapsed_millis = ?
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND harness_owner = ?::uuid
                       AND harness_lease_until > clock_timestamp() AND status = 'running'
                    """, round, input, output, elapsed, tenantId, executionId, owner);
            if (updated != 1) {
                throw new LostOwnershipException();
            }
        }

        void requireReview() {
            int updated = jdbc.update("""
                    UPDATE sf_agent_execution SET harness_recoverable = false
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND harness_owner = ?::uuid
                       AND harness_lease_until > clock_timestamp() AND status = 'running'
                    """, tenantId, executionId, owner);
            if (updated != 1) {
                throw new LostOwnershipException();
            }
        }

        void claimTool(int round, ToolExecutionRequest request) {
            int inserted = jdbc.update("""
                    INSERT INTO sf_agent_tool_dispatch (tenant_id, execution_id, round_number, call_id, tool_code)
                    SELECT tenant_id, id, ?, ?, ? FROM sf_agent_execution
                     WHERE tenant_id = ?::uuid AND id = ?::uuid AND harness_owner = ?::uuid
                       AND harness_lease_until > clock_timestamp() AND status = 'running'
                     FOR SHARE
                    ON CONFLICT DO NOTHING
                    """, round, request.id(), request.name(), tenantId, executionId, owner);
            if (inserted != 1) {
                throw new LostOwnershipException();
            }
        }

        void recordTool(int round, AgentLoopToolHandler.ToolExecutionOutcome outcome) {
            ToolExecutionResultMessage result = outcome.resultMessage();
            boolean review = !outcome.toolResult().isSuccess() || outcome.toolResult().getControlAction() != null;
            jdbc.update("""
                    UPDATE sf_agent_tool_dispatch call
                       SET receipt = ?, requires_review = ?
                      FROM sf_agent_execution run
                     WHERE call.tenant_id = run.tenant_id AND call.execution_id = run.id
                       AND run.tenant_id = ?::uuid AND run.id = ?::uuid AND run.harness_owner = ?::uuid
                       AND call.round_number = ? AND call.call_id = ? AND call.receipt IS NULL
                    """, ChatMessageSerializer.messageToJson(result), review, tenantId, executionId, owner, round, result.id());
        }

        List<Dispatch> dispatches() {
            return jdbc.query("""
                    SELECT call_id, tool_code, receipt, requires_review
                      FROM sf_agent_tool_dispatch
                     WHERE tenant_id = ?::uuid AND execution_id = ?::uuid AND round_number = ?
                    """, (row, index) -> {
                String receipt = row.getString("receipt");
                return new Dispatch(row.getString("call_id"), row.getString("tool_code"), receipt == null ? null
                        : (ToolExecutionResultMessage) ChatMessageDeserializer.messageFromJson(receipt),
                        row.getBoolean("requires_review"));
            }, tenantId, executionId, checkpoint.round());
        }

        @Override
        public void close() {
            if (CURRENT.get() == this) {
                CURRENT.remove();
            }
            renewal.cancel(false);
            try {
                jdbc.update("""
                        UPDATE sf_agent_execution SET harness_lease_until = clock_timestamp()
                         WHERE tenant_id = ?::uuid AND id = ?::uuid AND harness_owner = ?::uuid
                        """, tenantId, executionId, owner);
            } catch (RuntimeException exception) {
                log.warn("Agent 执行租约释放失败，executionId={}", executionId);
            }
        }
    }

    record Checkpoint(int round, boolean recoverable) { }

    record Dispatch(String id, String tool, ToolExecutionResultMessage receipt, boolean requiresReview) { }

    static final class LostOwnershipException extends IllegalStateException {
        LostOwnershipException() {
            super("执行租约已失效或工具调用已被领取");
        }
    }
}
