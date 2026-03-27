package com.schemaplexai.service.agent.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Agent 执行事件 MQ 消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutionEventMqConsumer {

    private final ExecutionEventStreamService executionEventStreamService;

    @RabbitListener(queues = "#{agentExecEventQueue.name}")
    public void onEvent(AgentExecutionEvent event) {
        if (event == null) {
            return;
        }
        executionEventStreamService.dispatchLocal(event);
    }
}
