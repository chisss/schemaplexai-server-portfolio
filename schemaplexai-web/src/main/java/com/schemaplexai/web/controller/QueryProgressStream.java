package com.schemaplexai.web.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

@Component
public class QueryProgressStream {
    private final Executor executor;
    private final TaskDecorator identity;

    public QueryProgressStream(@Qualifier("queryPlanningPool") Executor executor, TaskDecorator identity) {
        this.executor = executor;
        this.identity = identity;
    }

    SseEmitter start(Consumer<Consumer<Object>> task) {
        var emitter = new SseEmitter(120_000L);
        try {
            executor.execute(identity.decorate(new DelegatingSecurityContextRunnable(() -> {
                try {
                    task.accept(event -> send(emitter, event));
                    emitter.complete();
                } catch (Disconnected ignored) {
                    emitter.complete();
                } catch (RuntimeException failure) {
                    sendError(emitter, "查询计划生成失败，请重试。");
                }
            })));
        } catch (RejectedExecutionException busy) {
            sendError(emitter, "查询规划繁忙，请稍后重试。");
        }
        return emitter;
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            send(emitter, Map.of("type", "error", "message", message));
        } catch (Disconnected ignored) {
            // 客户端已经关闭连接。
        } finally {
            emitter.complete();
        }
    }

    private void send(SseEmitter emitter, Object event) {
        try {
            emitter.send(SseEmitter.event().data(event, org.springframework.http.MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException closed) {
            throw new Disconnected(closed);
        }
    }

    private static final class Disconnected extends RuntimeException {
        private Disconnected(Exception cause) {
            super(cause);
        }
    }
}
