package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.EXECUTION;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.OTHER_TENANT;
import static com.schemaplexai.service.agent.execution.AgentHarnessPostgres.TENANT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentTerminalEventOutboxTest {

    @Test
    void rollsBackTheEventWithStateAndReplaysTheCommittedEventAfterRestart() {
        try (var db = new AgentHarnessPostgres()) {
            db.insert("running");
            var mapper = mock(AgentExecutionMapper.class);
            when(mapper.update(any(AgentExecution.class), any())).thenAnswer(call -> db.jdbc.update("""
                    UPDATE sf_agent_execution SET status = ? WHERE id = ?::uuid AND status = 'running'
                    """, call.<AgentExecution>getArgument(0).getStatus(), EXECUTION));
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AgentExecution.class);
            var json = new ObjectMapper().findAndRegisterModules();
            var firstProcess = new AgentTerminalEventOutbox(db.jdbc, json);
            var logs = new AgentLogService(mock(AgentExecutionLogMapper.class), mapper,
                    mock(ApplicationEventPublisher.class), firstProcess);
            var tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource));
            var event = AgentExecutionEvent.builder().eventType("COMPLETED").executionId(EXECUTION)
                    .message("已交付").timestamp(Instant.now()).build();

            tx.executeWithoutResult(status -> {
                assertTrue(logs.tryFinalizeWithEvent(EXECUTION, "completed", null, 1L, 2L, "完成", null, event));
                status.setRollbackOnly();
            });
            assertEquals("running", db.jdbc.queryForObject("SELECT status FROM sf_agent_execution", String.class));
            assertTrue(firstProcess.find(TENANT, EXECUTION).isEmpty());
            tx.executeWithoutResult(status ->
                    assertTrue(logs.tryFinalizeWithEvent(EXECUTION, "completed", null, 1L, 2L, "完成", null, event)));

            var nextProcess = new AgentTerminalEventOutbox(db.jdbc, json);
            assertEquals(EXECUTION + ":terminal", nextProcess.find(TENANT, EXECUTION).orElseThrow().getEventId());
            assertTrue(nextProcess.find(OTHER_TENANT, EXECUTION).isEmpty());
            assertFalse(logs.tryFinalizeWithEvent(EXECUTION, "failed", "重复终态", 1L, 2L, null, null, event));

            var stream = mock(ExecutionEventStreamService.class);
            doThrow(new IllegalStateException("测试 MQ 暂不可用")).doNothing().when(stream).publishConfirmed(any());
            var relay = new AgentTerminalEventRelay(nextProcess, stream);
            relay.publishPending();
            assertTrue(nextProcess.claim(1).isEmpty());
            db.jdbc.update("UPDATE sf_agent_terminal_event SET next_attempt_at = clock_timestamp()");
            relay.publishPending();
            verify(stream, times(2)).publishConfirmed(event);
            assertTrue(nextProcess.claim(1).isEmpty());
            assertTrue(nextProcess.find(TENANT, EXECUTION).isPresent());
        }
    }

    @Test
    void claimsOnceAcrossWorkersAndCanRetryAfterRelayDies() {
        try (var db = new AgentHarnessPostgres()) {
            db.insert("completed");
            var outbox = new AgentTerminalEventOutbox(db.jdbc, new ObjectMapper().findAndRegisterModules());
            outbox.enqueue(AgentExecutionEvent.builder().eventType("COMPLETED").executionId(EXECUTION).build());
            var initial = outbox.claim(1).getFirst();
            assertTrue(outbox.claim(1).isEmpty());
            db.jdbc.update("UPDATE sf_agent_terminal_event SET next_attempt_at = clock_timestamp()");
            var next = outbox.claim(1).getFirst();
            outbox.confirm(initial);
            assertEquals(1L, db.jdbc.queryForObject(
                    "SELECT count(*) FROM sf_agent_terminal_event WHERE published_at IS NULL", Long.class));
            outbox.confirm(next);
            assertTrue(outbox.claim(1).isEmpty());
        }
    }
}
