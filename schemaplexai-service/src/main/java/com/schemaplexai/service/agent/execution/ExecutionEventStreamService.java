package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.schemaplexai.common.util.SecurityUtil;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 执行事件流服务（本地节点内存版）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionEventStreamService {

    private static final long SSE_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final DefaultMessagePropertiesConverter MQ_PROPERTIES = new DefaultMessagePropertiesConverter();
    private static final String AGENT_EXEC_EVENT_EXCHANGE = "sf.agent.exec.event";

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitterMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SseEmitter, String> deliveredTerminalEvents = new ConcurrentHashMap<>();
    private final RabbitTemplate rabbitTemplate;
    private final AgentLogService agentLogService;
    private final ObjectMapper objectMapper;
    private final AgentTerminalEventOutbox terminalOutbox;

    public void publishActivity(String executionId, String agentId, String tenantId, AgentExecutionActivity activity) {
        try {
            agentLogService.appendLog(executionId, agentId, tenantId, "INFO", AgentExecutionActivity.EVENT_TYPE,
                    activity.round(), null, objectMapper.writeValueAsString(activity), null, null);
        } catch (Exception e) {
            log.warn("执行活动存档失败: executionId={}, activityId={}", executionId, activity.id(), e);
        }
        publishActivityProgress(executionId, activity);
    }

    public void publishActivityProgress(String executionId, AgentExecutionActivity activity) {
        publish(AgentExecutionEvent.builder()
                .eventType(AgentExecutionActivity.EVENT_TYPE)
                .executionId(executionId)
                .roundNum(activity.round())
                .message(activity.summary())
                .payload(activity)
                .timestamp(activity.updatedAt())
                .build());
    }

    public List<AgentExecutionActivity> recentActivity(String executionId) {
        Map<String, AgentExecutionActivity> latest = new LinkedHashMap<>();
        for (var row : agentLogService.recentActivity(executionId)) {
            try {
                var activity = objectMapper.readValue(row.getContent(), AgentExecutionActivity.class);
                latest.merge(activity.id(), activity,
                        (a, b) -> a.updatedAt().isAfter(b.updatedAt()) ? a : b);
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("执行活动记录解析失败: executionId={}", executionId, e);
            }
        }
        return latest.values().stream().sorted(Comparator.comparing(AgentExecutionActivity::startedAt)).toList();
    }

    public SseEmitter subscribe(String executionId) {
        return subscribe(executionId, null);
    }

    public SseEmitter subscribe(String executionId, String lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        if ((executionId + ":terminal").equals(lastEventId)) {
            deliveredTerminalEvents.put(emitter, lastEventId);
        }
        emitterMap.compute(executionId, (id, current) -> {
            var emitters = current == null ? new CopyOnWriteArrayList<SseEmitter>() : current;
            emitters.add(emitter);
            return emitters;
        });

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
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId != null) {
            try {
                terminalOutbox.find(tenantId, executionId).ifPresent(event -> send(executionId, emitter, event));
            } catch (RuntimeException e) {
                removeEmitter(executionId, emitter);
                throw e;
            }
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

    /** 专用发布确认，失败交给 Outbox 重试。 */
    void publishConfirmed(AgentExecutionEvent event) {
        var properties = new MessageProperties();
        properties.setMessageId(event.getEventId());
        var message = rabbitTemplate.getMessageConverter().toMessage(event, properties);
        rabbitTemplate.execute(channel -> {
            channel.confirmSelect();
            channel.basicPublish(AGENT_EXEC_EVENT_EXCHANGE, "", MQ_PROPERTIES.fromMessageProperties(message.getMessageProperties(), "UTF-8"), message.getBody());
            channel.waitForConfirmsOrDie(3_000);
            return null;
        });
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
        for (SseEmitter emitter : emitters) {
            send(event.getExecutionId(), emitter, event);
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

    private void send(String executionId, SseEmitter emitter, AgentExecutionEvent event) {
        synchronized (emitter) {
            String eventId = event.getEventId();
            if (eventId != null && eventId.equals(deliveredTerminalEvents.get(emitter))) {
                return;
            }
            try {
                var message = SseEmitter.event().name("message").data(event);
                if (eventId != null) {
                    message.id(eventId);
                }
                emitter.send(message);
                if (eventId != null) {
                    deliveredTerminalEvents.put(emitter, eventId);
                }
            } catch (IOException | IllegalStateException e) {
                removeEmitter(executionId, emitter);
            }
        }
    }

    private void removeEmitter(String executionId, SseEmitter emitter) {
        deliveredTerminalEvents.remove(emitter);
        emitterMap.computeIfPresent(executionId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
