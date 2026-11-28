package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.agent.runtime.team.LangGraphTeamRuntimeAdapter;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTelemetryTest {
    @Test
    void exportsOneTraceAcrossGraphMemberAndModelOrToolSpans() throws Exception {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            GlobalOpenTelemetry.resetForTest();
            OpenTelemetrySdk.builder().setTracerProvider(provider).buildAndRegisterGlobal();
            var root = AgentTelemetry.execution("team", AgentExecutionContext.builder().executionId("team-1").build());
            try (var scope = root.makeCurrent()) {
                var adapter = new LangGraphTeamRuntimeAdapter();
                var emitter = adapter.buildRunnableConfig("test").emitterExecutor().orElseThrow();
                CompletableFuture.runAsync(() -> {
                    var context = AgentExecutionContext.builder().executionId("child-1").parentExecutionId("team-1")
                            .traceParent(AgentTelemetry.capture()).build();
                    var child = AgentTelemetry.execution("member", context);
                    try (var memberScope = child.makeCurrent()) {
                        AgentTelemetry.operation("agent.model", SpanKind.CLIENT, "gen_ai.request.model", "fixture").end();
                        AgentTelemetry.operation("agent.tool", SpanKind.CLIENT, "agent.tool.name", "read_fixture").end();
                    } finally {
                        child.end();
                    }
                }, emitter).get(5, TimeUnit.SECONDS);
            } finally {
                root.end();
            }

            var spans = exporter.getFinishedSpanItems();
            assertThat(spans).extracting(span -> span.getName())
                    .containsExactly("agent.model", "agent.tool", "agent.member", "agent.team");
            assertThat(spans).allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(root.getSpanContext().getTraceId()));
            assertThat(spans.get(2).getParentSpanId()).isEqualTo(spans.getLast().getSpanId());
            assertThat(spans.get(0).getParentSpanId()).isEqualTo(spans.get(2).getSpanId());
            assertThat(spans.get(1).getParentSpanId()).isEqualTo(spans.get(2).getSpanId());
        } finally {
            GlobalOpenTelemetry.resetForTest();
        }
    }
}
