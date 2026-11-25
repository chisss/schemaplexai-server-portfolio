package com.schemaplexai.service.integration.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 兼容仍使用 GET 订阅和独立 POST 通道的旧版 MCP 服务器。 */
final class LegacySseMcpTransport implements McpTransport {
    private static final ObjectMapper WIRE_JSON = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json");
    private final HttpUrl streamUrl;
    private final McpHeadersSupplier headers;
    private final OkHttpClient client;
    private volatile Session session;
    private volatile Runnable failureListener = () -> {};
    private boolean closed;

    LegacySseMcpTransport(String streamUrl, McpHeadersSupplier headers, Duration timeout) {
        this.streamUrl = HttpUrl.get(streamUrl);
        this.headers = headers;
        client = new OkHttpClient.Builder()
                .callTimeout(timeout)
                .connectTimeout(timeout)
                .writeTimeout(timeout)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build();
    }

    @Override
    public synchronized void start(McpOperationHandler handler) {
        if (closed) {
            throw new IllegalStateException("MCP SSE 传输已关闭");
        }
        stop();
        Session next = new Session(handler);
        session = next;
        Request request = requestBuilder(streamUrl, null).header("Accept", "text/event-stream").build();
        next.events = EventSources.createFactory(client).newEventSource(request, new StreamListener(next));
    }

    @Override
    public synchronized void stop() {
        Session previous = session;
        session = null;
        if (previous != null) {
            previous.endpoint.completeExceptionally(new IOException("MCP SSE 会话已结束"));
            if (previous.events != null) {
                previous.events.cancel();
            }
            client.dispatcher().cancelAll();
            previous.handler.cancelAllPendingOperations("MCP SSE 会话已结束");
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            stop();
            closed = true;
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return sendRequest(new McpCallContext(null, request)).thenCompose(response ->
                send(new McpCallContext(null, new McpInitializationNotification()), false)
                        .thenApply(ignored -> response));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        return send(context, true);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        send(context, false);
    }

    @Override
    public void checkHealth() {
        Session active = session;
        if (active == null || !active.endpoint.isDone() || active.endpoint.isCompletedExceptionally() || active.failed) {
            throw new IllegalStateException("MCP SSE 通道未就绪");
        }
    }

    @Override
    public void onFailure(Runnable actionOnFailure) {
        failureListener = actionOnFailure;
    }

    private CompletableFuture<String> send(McpCallContext context, boolean awaitResponse) {
        Session active = session;
        if (active == null || active.failed) {
            return CompletableFuture.failedFuture(new IOException("MCP SSE 通道已断开"));
        }
        return active.endpoint.thenCompose(url -> post(active, url, context, awaitResponse));
    }

    private synchronized CompletableFuture<String> post(Session active, HttpUrl url, McpCallContext context,
                                                         boolean awaitResponse) {
        if (session != active || active.failed) {
            return CompletableFuture.failedFuture(new IOException("MCP SSE 通道已断开"));
        }

        Request request;
        try {
            request = requestBuilder(url, context)
                    .header("Accept", "application/json")
                    .post(RequestBody.create(WIRE_JSON.writeValueAsBytes(context.message()), JSON))
                    .build();
        } catch (IOException | IllegalArgumentException failure) {
            return CompletableFuture.failedFuture(failure);
        }
        CompletableFuture<String> response = new CompletableFuture<>();
        Long id = context.message().getId();
        if (awaitResponse && id != null) {
            active.handler.expectResponse(id, response);
        }
        Call call = client.newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                response.completeExceptionally(e);
            }

            @Override
            public void onResponse(Call call, Response accepted) {
                try (accepted) {
                    if (!accepted.isSuccessful()) {
                        response.completeExceptionally(new IOException("MCP SSE POST 返回 HTTP " + accepted.code()));
                    } else if (!awaitResponse || id == null) {
                        response.complete(null);
                    }
                }
            }
        });
        response.whenComplete((result, error) -> {
            if (response.isCancelled()) {
                call.cancel();
            }
        });
        return response;
    }

    private Request.Builder requestBuilder(HttpUrl url, McpCallContext context) {
        Request.Builder request = new Request.Builder().url(url);
        Map<String, String> defaults = headers.apply(context);
        if (defaults != null) {
            defaults.forEach(request::header);
        }
        if (context != null && context.mcpParamHeaders() != null) {
            context.mcpParamHeaders().forEach(request::header);
        }
        return request;
    }

    private final class StreamListener extends EventSourceListener {
        private final Session active;

        private StreamListener(Session active) {
            this.active = active;
        }

        @Override
        public void onEvent(EventSource events, String id, String type, String data) {
            if (active != session || active.failed) {
                return;
            }
            if ("endpoint".equals(type)) {
                HttpUrl endpoint = streamUrl.resolve(data.trim());
                if (endpoint != null && endpoint.scheme().equals(streamUrl.scheme())
                        && endpoint.host().equals(streamUrl.host()) && endpoint.port() == streamUrl.port()
                        && endpoint.username().isEmpty() && endpoint.password().isEmpty()) {
                    active.endpoint.complete(endpoint);
                } else {
                    active.endpoint.completeExceptionally(new IOException("MCP SSE POST 端点必须与订阅端点同源"));
                    events.cancel();
                }
            } else if (type == null || "message".equals(type)) {
                active.handler.onMessage(data);
            }
        }

        @Override
        public void onClosed(EventSource events) {
            disconnected();
        }

        @Override
        public void onFailure(EventSource events, Throwable error, Response response) {
            disconnected();
        }

        private void disconnected() {
            if (active != session) {
                return;
            }
            active.failed = true;
            boolean handshakeFailed = active.endpoint.completeExceptionally(new IOException("MCP SSE 通道已断开"));
            active.handler.cancelAllPendingOperations("MCP SSE 通道已断开");
            if (!handshakeFailed && !active.endpoint.isCompletedExceptionally()) {
                failureListener.run();
            }
        }
    }

    private static final class Session {
        private final McpOperationHandler handler;
        private final CompletableFuture<HttpUrl> endpoint = new CompletableFuture<>();
        private EventSource events;
        private volatile boolean failed;

        private Session(McpOperationHandler handler) {
            this.handler = handler;
        }
    }
}
