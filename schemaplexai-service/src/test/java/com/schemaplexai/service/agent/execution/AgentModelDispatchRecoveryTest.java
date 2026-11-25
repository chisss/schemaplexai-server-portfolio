package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.EXECUTION;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

class AgentModelDispatchRecoveryTest {

    @Test
    void retainsUnknownReservationsAndSettlesKnownAttemptsExactlyOnce() {
        try (var db = new AgentHarnessPostgres()) {
            var recovery = new AgentRunRecovery(db.jdbc);
            try {
                db.insert("queued");
                var context = AgentExecutionContext.builder().tenantId(TENANT).executionId(EXECUTION).build();
                try (var lease = recovery.claim(context, "queued", false)) {
                    db.jdbc.update("UPDATE sf_agent_execution SET status = 'running'");
                    lease.checkpoint(1, 0, 0, 15);
                    var known = UUID.randomUUID().toString();
                    lease.reserveModel(known, 1, "inference", 20, 30, 100, 50);
                    lease.settleModel(known, 19, 4, 55);
                    lease.settleModel(known, 19, 4, 56);
                    assertEquals(23, used(db));
                    var unknown = UUID.randomUUID().toString();
                    lease.reserveModel(unknown, 2, "inference", 20, 40, 100, 60);
                    expire(db);
                    try (var takeover = recovery.claim(context, "running", true)) {
                        assertNotNull(takeover);
                        assertEquals(83, used(db));
                        assertThrows(AgentRunRecovery.LostOwnershipException.class, lease::checkOwnership);
                        lease.settleModel(unknown, 20, 1, 100);
                        assertEquals(83, used(db));
                        assertThrows(AgentExecutionBudget.BudgetExceededException.class, () ->
                                takeover.reserveModel(UUID.randomUUID().toString(), 2, "inference", 10, 8, 100, 80));
                    }
                }
            } finally {
                recovery.shutdown();
            }
        }
    }

    @Test
    void fencesModelBeforeTheProviderRunsAfterAReservation() {
        try (var db = new AgentHarnessPostgres()) {
            var recovery = new AgentRunRecovery(db.jdbc);
            try {
                db.insert("queued");
                var context = AgentExecutionContext.builder().tenantId(TENANT).executionId(EXECUTION).build();
                try (var lease = recovery.claim(context, "queued", false)) {
                    db.jdbc.update("UPDATE sf_agent_execution SET status = 'running'");
                    var fenced = spy(lease);
                    doAnswer(invocation -> {
                        expire(db);
                        return invocation.callRealMethod();
                    }).when(fenced).checkOwnership();
                    long start = System.currentTimeMillis();
                    var budget = new AgentExecutionBudget(start + 10_000, 2048, 0, start,
                            new AgentLoopState(), () -> false, null, fenced, false);
                    var provider = mock(ChatModel.class);
                    var invoker = new AgentModelInvoker(mock(AgentHookExecutor.class), mock(ModelMetricsTracker.class),
                            mock(RouteAnalysisService.class), mock(AgentTraceService.class), mock(LangChain4jModelFactory.class));

                    assertThrows(AgentRunRecovery.LostOwnershipException.class, () ->
                            invoker.invokeWithRetry(new LangChain4jResolution(provider, null),
                                    ChatRequest.builder().messages(UserMessage.from("恢复测试")).maxOutputTokens(20).build(),
                                    AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build(),
                                    EXECUTION, "agent", TENANT, 1, start, mock(AgentLogService.class), budget, "inference"));
                    verifyNoInteractions(provider);
                    assertEquals(1L, db.jdbc.queryForObject("SELECT count(*) FROM sf_agent_model_attempt", Long.class));
                }
            } finally {
                recovery.shutdown();
            }
        }
    }

    private void expire(AgentHarnessPostgres db) {
        db.jdbc.update("UPDATE sf_agent_execution SET harness_lease_until = clock_timestamp()");
    }

    private long used(AgentHarnessPostgres db) {
        return db.jdbc.queryForObject("SELECT token_input + token_output FROM sf_agent_execution", Long.class);
    }
}
