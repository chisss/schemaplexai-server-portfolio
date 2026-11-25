package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.entity.AgentExecutionLog;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionEventStreamServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AgentLogService logs = mock(AgentLogService.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final ExecutionEventStreamService service = new ExecutionEventStreamService(rabbit, logs, json);

    @Test
    void shouldPersistCompletedStepAndKeepIsoDatesAcrossMq() throws Exception {
        var started = AgentExecutionActivity.start(AgentExecutionActivity.Kind.READ, "读取文件", "README.md",
                "研发", 1, Map.of("path", "README.md"));
        var finished = started.finish(true, started.summary(), null, started.fields());

        service.publishActivity("root", "agent", "tenant", finished);

        var content = ArgumentCaptor.forClass(String.class);
        verify(logs).appendLog(eq("root"), eq("agent"), eq("tenant"), eq("INFO"),
                eq("ACTIVITY_STEP"), eq(1), eq(null), content.capture(), eq(null), eq(null));
        var event = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(rabbit).convertAndSend(eq("sf.agent.exec.event"), eq(""), event.capture());
        var converter = new Jackson2JsonMessageConverter();
        var message = converter.toMessage(event.getValue(), new MessageProperties());
        var received = (AgentExecutionEvent) converter.fromMessage(message);
        assertThat(received.getPayload()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) received.getPayload()).get("startedAt")).isEqualTo(started.startedAt().toString());
        assertThat(json.readValue(content.getValue(), AgentExecutionActivity.class)).isEqualTo(finished);
    }

    @Test
    void shouldReplayLatestSnapshotForEachStepInStartOrder() throws Exception {
        var analysis = AgentExecutionActivity.start(AgentExecutionActivity.Kind.ANALYSIS, "分析任务", null,
                null, 1, Map.of());
        var tool = AgentExecutionActivity.start(AgentExecutionActivity.Kind.FUNCTION, "执行函数", "verify",
                null, 1, Map.of());
        var finished = analysis.finish(true, "计划调用 1 个工具", "先核对输入", Map.of());
        when(logs.recentActivity("root")).thenReturn(List.of(row(tool), row(analysis), row(finished)));

        assertThat(service.recentActivity("root")).containsExactly(finished, tool);
    }

    private AgentExecutionLog row(AgentExecutionActivity step) throws Exception {
        var row = new AgentExecutionLog();
        row.setContent(json.writeValueAsString(step));
        return row;
    }
}
