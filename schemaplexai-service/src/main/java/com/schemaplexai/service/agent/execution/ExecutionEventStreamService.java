package com.schemaplexai.service.agent.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行事件流服务（本地节点内存版）
 */
@Slf4j
@Service
public class ExecutionEventStreamService {

    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final String AGENT_EXEC_EVENT_EXCHANGE = "sf.agent.exec.event";

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitterMap = new ConcurrentHashMap<>();
    private final RabbitTemplate rabbitTemplate;

    public ExecutionEventStreamService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public SseEmitter subscribe(String executionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitterMap.computeIfAbsent(executionId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onTimeout(() -> removeEmitter(executionId, emitter));
        emitter.onCompletion(() -> removeEmitter(executionId, emitter));
        emitter.onError(ex -> removeEmitter(executionId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(AgentExecutionEvent.builder()
                            .eventType("CONNECTED")
                            .executionId(executionId)
                            .message("事件流连接已建立")
                            .timestamp(Instant.now())
                            .build()));
        } catch (IOException e) {
            removeEmitter(executionId, emitter);
            log.warn("SSE 建连后首次发送失败: executionId={}", executionId, e);
        }
        return emitter;
    }

    public void publish(AgentExecutionEvent event) {
        if (event == null || event.getExecutionId() == null) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(AGENT_EXEC_EVENT_EXCHANGE, "", event);
        } catch (Exception e) {
            // MQ 发布失败时降级到本地分发，避免事件丢失
            log.warn("执行事件MQ发布失败，降级本地分发: executionId={}", event.getExecutionId(), e);
            dispatchLocal(event);
        }
    }

    /**
     * 将事件分发到本节点活跃 SSE 连接
     */
    public void dispatchLocal(AgentExecutionEvent event) {
        if (event == null || event.getExecutionId() == null) {
            return;
        }
        CopyOnWriteArrayList<SseEmitter> emitters = emitterMap.get(event.getExecutionId());
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> closedEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("message").data(event));
            } catch (IOException e) {
                closedEmitters.add(emitter);
            }
        }
        if (!closedEmitters.isEmpty()) {
            emitters.removeAll(closedEmitters);
            cleanupIfEmpty(event.getExecutionId(), emitters);
        }
    }

    /**
     * 发布简单事件（无 payload）
     */
    public void publishSimple(String executionId, String eventType, int round, String message, long startMs) {
        publish(AgentExecutionEvent.builder()
                .eventType(eventType)
                .executionId(executionId)
                .roundNum(round)
                .message(message)
                .elapsedMs(System.currentTimeMillis() - startMs)
                .timestamp(Instant.now())
                .build());
    }

    /**
     * 发布带 payload 的事件
     */
    public void publishWithPayload(String executionId, String eventType, int round, String message, Object payload, long startMs) {
        publish(AgentExecutionEvent.builder()
                .eventType(eventType)
                .executionId(executionId)
                .roundNum(round)
                .message(message)
                .payload(payload)
                .elapsedMs(System.currentTimeMillis() - startMs)
                .timestamp(Instant.now())
                .build());
    }

    private void removeEmitter(String executionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = emitterMap.get(executionId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        cleanupIfEmpty(executionId, emitters);
    }

    private void cleanupIfEmpty(String executionId, CopyOnWriteArrayList<SseEmitter> emitters) {
        if (emitters.isEmpty()) {
            emitterMap.remove(executionId, emitters);
        }
    }
}
