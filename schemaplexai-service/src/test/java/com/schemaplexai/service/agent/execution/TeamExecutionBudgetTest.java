package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.EXECUTION;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.OTHER_TENANT;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamExecutionBudgetTest {

    @Test
    void concurrentMembersShareThePersistedBalanceAcrossPauseAndResume() throws Exception {
        try (AgentHarnessPostgres db = new AgentHarnessPostgres();
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            db.insert("running");
            var budgets = new TeamExecutionBudget(db.jdbc);
            long deadline = System.currentTimeMillis() + 120_000L;
            var account = budgets.open(TENANT, EXECUTION, 100, deadline, 10);
            var gate = new CountDownLatch(1);
            var first = threads.submit(() -> {
                gate.await();
                return account.reserve(15, 70);
            });
            var second = threads.submit(() -> {
                gate.await();
                return account.reserve(15, 70);
            });
            gate.countDown();
            long consumed = first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS);
            assertEquals(90, consumed);
            assertEquals(100 - consumed, account.forFinalization().remaining());
            assertEquals(0, account.reserve(10, 50));
            account.settle(consumed, 55);
            assertEquals(35, account.remaining());
            db.jdbc.update("UPDATE sf_agent_execution SET status = 'paused', "
                    + TeamExecutionBudget.FREEZE_ON_PAUSE + " WHERE id = ?::uuid", EXECUTION);
            assertEquals(0, account.reserve(1, 5));
            long remainingMillis = db.jdbc.queryForObject("SELECT team_active_remaining_ms FROM sf_agent_execution", Long.class);
            long beforeResume = System.currentTimeMillis();
            db.jdbc.update("UPDATE sf_agent_execution SET status = 'running' WHERE id = ?::uuid", EXECUTION);
            var reopened = new TeamExecutionBudget(db.jdbc).open(TENANT, EXECUTION, 500, deadline + 200_000L, 0);
            long afterResume = System.currentTimeMillis();
            assertTrue(reopened.deadlineMs() >= beforeResume + remainingMillis - 2);
            assertTrue(reopened.deadlineMs() <= afterResume + remainingMillis + 2);
            assertEquals(35, reopened.remaining());
            assertEquals(45, reopened.forFinalization().reserve(1, 90));
            assertThrows(IllegalStateException.class, () -> budgets.open(OTHER_TENANT, EXECUTION, 100, deadline, 0));
        }
    }

    @Test
    void allowsAnInFlightRequestToUseTheLastSharedReservation() throws Exception {
        try (AgentHarnessPostgres db = new AgentHarnessPostgres()) {
            db.insert("running");
            long start = System.currentTimeMillis();
            var account = new TeamExecutionBudget(db.jdbc).open(TENANT, EXECUTION, 1_000, start + 30_000, 0);
            var model = mock(ChatModel.class);
            when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
                assertEquals(0, account.remaining());
                return ChatResponse.builder().aiMessage(AiMessage.from("已完成"))
                        .tokenUsage(new TokenUsage(20, 10)).build();
            });
            var invoker = new AgentModelInvoker(mock(AgentHookExecutor.class), mock(ModelMetricsTracker.class),
                    mock(RouteAnalysisService.class), mock(AgentTraceService.class), mock(LangChain4jModelFactory.class));
            var budget = new AgentExecutionBudget(start + 30_000, 2_000, 0, start,
                    new AgentLoopState(), () -> false, account);

            var result = invoker.invokeWithRetry(new LangChain4jResolution(model, null),
                    ChatRequest.builder().messages(UserMessage.from("协作任务")).build(),
                    AgentEngineParams.builder().modelCallTimeoutMillis(2_000).build(),
                    EXECUTION, "agent", TENANT, 1, start, mock(AgentLogService.class), budget, "inference");

            assertEquals("已完成", result.aiMessage().text());
            assertEquals(970, account.remaining());
        }
    }

    @Test
    void expiredRootCannotAllocateModelTokens() {
        try (AgentHarnessPostgres db = new AgentHarnessPostgres()) {
            db.insert("running");
            var account = new TeamExecutionBudget(db.jdbc).open(TENANT, EXECUTION, 100,
                    System.currentTimeMillis() - 1_000L, 0);
            assertEquals(0, account.reserve(1, 100));
            assertEquals(100, account.remaining());
        }
    }
}
