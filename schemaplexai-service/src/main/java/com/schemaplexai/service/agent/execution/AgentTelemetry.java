package com.schemaplexai.service.agent.execution;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;

/** 执行链路追踪只记录标识与状态，提示和工具参数留在业务存档。 */
public final class AgentTelemetry {
    private AgentTelemetry() { }

    public static Span execution(String kind, AgentExecutionContext context) {
        var builder = GlobalOpenTelemetry.getTracer("schemaplexai.agent").spanBuilder("agent." + kind)
                .setSpanKind(SpanKind.INTERNAL);
        if (context != null) {
            if (context.getTraceParent() != null) builder.setParent(context.getTraceParent());
            if (context.getExecutionId() != null) builder.setAttribute("agent.execution.id", context.getExecutionId());
            if (context.getParentExecutionId() != null) builder.setAttribute("agent.parent_execution.id", context.getParentExecutionId());
        }
        return builder.startSpan();
    }

    public static Span operation(String name, SpanKind kind, String attribute, String value) {
        return GlobalOpenTelemetry.getTracer("schemaplexai.agent").spanBuilder(name)
                .setSpanKind(kind).setAttribute(attribute, value).startSpan();
    }

    public static Context capture() {
        return Context.current();
    }

    public static void outcome(Span span, AgentExecutionResult result) {
        if (result == null || result.getStatus() == null) return;
        span.setAttribute("agent.execution.status", result.getStatus());
        if ("failed".equals(result.getStatus())) span.setStatus(StatusCode.ERROR);
    }

    public static void failure(Span span, Throwable failure) {
        span.setAttribute("error.type", failure.getClass().getSimpleName());
        span.setStatus(StatusCode.ERROR);
    }
}
