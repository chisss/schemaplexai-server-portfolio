package com.schemaplexai.service.agent.execution;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class AgentTerminalEventRelay {
    private final AgentTerminalEventOutbox outbox;
    private final ExecutionEventStreamService stream;

    @Scheduled(fixedDelayString = "${schemaplexai.agent.terminal-event-poll-ms:1000}")
    void publishPending() {
        for (var delivery : outbox.claim(10)) {
            try {
                stream.publishConfirmed(outbox.decode(delivery.payload()));
                outbox.confirm(delivery);
            } catch (RuntimeException e) {
                log.warn("终态事件等待重投: executionId={}, errorType={}", delivery.executionId(),
                        e.getClass().getSimpleName());
                outbox.retry(delivery);
            }
        }
    }
}
