package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.model.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.postgresql.Driver;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.EXECUTION;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.OTHER_TENANT;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRunRecoveryTest {

    @Test
    void fencesTheFormerOwnerAndPreservesKnownAndUncertainDispatches() {
        try (AgentHarnessPostgres db = new AgentHarnessPostgres()) {
            var firstProcess = new AgentRunRecovery(db.jdbc);
            var secondProcess = new AgentRunRecovery(db.jdbc);
            try {
                db.insert("queued");
                AgentExecutionContext context = AgentExecutionContext.builder().tenantId(TENANT).executionId(EXECUTION).build();
                try (var first = firstProcess.claim(context, "queued", false)) {
                    assertNotNull(first);
                    db.jdbc.update("UPDATE sf_agent_execution SET status = 'running' WHERE id = ?::uuid", EXECUTION);
                    first.checkpoint(3, 27, 9, 500);
                    ToolExecutionRequest confirmed = call("known");
                    first.claimTool(3, confirmed);
                    var receipt = ToolExecutionResultMessage.from(confirmed, "已收到");
                    first.recordTool(3, new AgentLoopToolHandler.ToolExecutionOutcome(
                            ToolResult.builder().success(true).build(), receipt, LocalDateTime.now(), 10));
                    first.claimTool(3, call("unknown"));
                    assertThrows(AgentRunRecovery.LostOwnershipException.class, () -> first.claimTool(3, confirmed));
                    assertNull(secondProcess.claim(context, "running", true));
                    db.jdbc.update("""
                            UPDATE sf_agent_execution SET harness_lease_until = clock_timestamp() - interval '1 second'
                             WHERE id = ?::uuid
                            """, EXECUTION);
                    try (var second = secondProcess.claim(context, "running", true)) {
                        assertNotNull(second);
                        assertEquals(new AgentRunRecovery.Checkpoint(3, true), second.checkpoint());
                        assertEquals(27L, db.jdbc.queryForObject("SELECT token_input FROM sf_agent_execution", Long.class));
                        var calls = second.dispatches().stream()
                                .sorted(Comparator.comparing(AgentRunRecovery.Dispatch::id)).toList();
                        assertEquals("已收到", calls.getFirst().receipt().text());
                        assertNull(calls.getLast().receipt());
                        assertThrows(AgentRunRecovery.LostOwnershipException.class, () -> first.claimTool(3, call("late")));
                        assertNull(firstProcess.claim(AgentExecutionContext.builder()
                                .tenantId(OTHER_TENANT).executionId(EXECUTION).build(), "running", true));
                    }
                }
                db.jdbc.update("UPDATE sf_agent_execution SET status = 'completed' WHERE id = ?::uuid", EXECUTION);
                assertNull(secondProcess.claim(context, "running", true));
            } finally {
                firstProcess.shutdown();
                secondProcess.shutdown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-dispatch", "after-effect", "after-receipt", "after-completion"})
    void preservesTheDispatchBoundaryWhenAnotherJvmIsKilled(String boundary, @TempDir Path directory) throws Exception {
        try (AgentHarnessPostgres db = new AgentHarnessPostgres()) {
            db.insert("queued");
            db.jdbc.execute("CREATE TABLE fixture_effect (call_id text NOT NULL)");
            String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            Path ready = directory.resolve("ready");
            Path output = directory.resolve("child.log");
            Process child = new ProcessBuilder(java, "-cp", classPath, CrashFixture.class.getName(),
                    db.schema(), boundary, ready.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (child.isAlive() && !Files.exists(ready) && System.nanoTime() < deadline) {
                    Thread.sleep(25L);
                }
                assertTrue(Files.exists(ready), () -> "子进程未到达故障注入点，日志 " + output);
            } finally {
                child.destroyForcibly();
                assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            }
            // 用数据库时间推进租约，测试进程接管而不等待生产租期。
            db.jdbc.update("UPDATE sf_agent_execution SET harness_lease_until = clock_timestamp()");
            var service = new AgentRunRecovery(db.jdbc);
            try {
                var context = AgentExecutionContext.builder().tenantId(TENANT).executionId(EXECUTION).build();
                try (var recovered = service.claim(context, "running", true)) {
                    int effects = db.jdbc.queryForObject("SELECT COUNT(*) FROM fixture_effect", Integer.class);
                    if (boundary.equals("after-completion")) {
                        assertNull(recovered);
                        assertEquals(1, effects);
                        return;
                    }
                    assertNotNull(recovered);
                    var dispatches = recovered.dispatches();
                    if (boundary.equals("before-dispatch")) {
                        assertTrue(dispatches.isEmpty());
                        assertEquals(0, effects);
                    } else {
                        assertEquals(1, effects);
                        assertEquals(1, dispatches.size());
                        assertEquals(boundary.equals("after-receipt"), dispatches.getFirst().receipt() != null);
                        assertThrows(AgentRunRecovery.LostOwnershipException.class,
                                () -> recovered.claimTool(1, call("write")));
                    }
                }
            } finally {
                service.shutdown();
            }
        }
    }

    public static final class CrashFixture {
        public static void main(String[] args) throws Exception {
            String url = System.getenv("AGENT_HARNESS_DB_URL");
            String user = System.getenv().getOrDefault("AGENT_HARNESS_DB_USER", "postgres");
            String password = System.getenv().getOrDefault("AGENT_HARNESS_DB_PASSWORD", "");
            var source = new SimpleDriverDataSource(new Driver(), url, user, password);
            var options = new Properties();
            options.setProperty("currentSchema", args[0]);
            source.setConnectionProperties(options);
            var jdbc = new JdbcTemplate(source);
            var recovery = new AgentRunRecovery(jdbc);
            var context = AgentExecutionContext.builder().tenantId(TENANT).executionId(EXECUTION).build();
            try (var lease = recovery.claim(context, "queued", false)) {
                jdbc.update("UPDATE sf_agent_execution SET status = 'running'");
                lease.checkpoint(1, 20, 5, 60);
                if (!args[1].equals("before-dispatch")) {
                    var request = call("write");
                    lease.claimTool(1, request);
                    jdbc.update("INSERT INTO fixture_effect VALUES (?)", request.id());
                    if (!args[1].equals("after-effect")) {
                        lease.recordTool(1, new AgentLoopToolHandler.ToolExecutionOutcome(
                                ToolResult.builder().success(true).build(), ToolExecutionResultMessage.from(request, "已确认"),
                                LocalDateTime.now(), 1));
                    }
                    if (args[1].equals("after-completion")) {
                        jdbc.update("UPDATE sf_agent_execution SET status = 'completed'");
                    }
                }
                Files.writeString(Path.of(args[2]), "ready");
                new CountDownLatch(1).await();
            } finally {
                recovery.shutdown();
            }
        }
    }

    private static ToolExecutionRequest call(String id) {
        return ToolExecutionRequest.builder().id(id).name("fixture_write").arguments("{}").build();
    }
}
