package com.schemaplexai.service.integration.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.credential.DatabaseCredentialMaterializer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChain4jMcpClientFactoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"legacy_sse", "streamable_http"})
    void shouldInitializeListAndCallTools(String transportType) throws Exception {
        boolean legacy = transportType.equals("legacy_sse");
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch closed = new CountDownLatch(1);
        AtomicReference<OutputStream> eventBody = new AtomicReference<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            http.setExecutor(executor);
            http.createContext("/mcp", exchange -> {
                if (legacy) {
                    subscribe(exchange, eventBody, closed);
                } else {
                    respond(exchange, null);
                }
            });
            http.createContext("/messages", exchange -> respond(exchange, eventBody.get()));
            http.start();
            try {
                McpServer server = new McpServer();
                server.setId("test-mcp");
                server.setTransportType(transportType);
                server.setUrl("http://127.0.0.1:" + http.getAddress().getPort() + "/mcp");
                DatabaseCredentialMaterializer materializer = mock(DatabaseCredentialMaterializer.class);
                when(materializer.materialize(server)).thenReturn(server);
                var factory = new LangChain4jMcpClientFactory(mock(DatabaseMcpPresetResolver.class), materializer);

                try (var client = factory.create(server)) {
                    assertThat(client.listTools()).extracting(ToolSpecification::name).containsExactly("lookup");
                    var request = ToolExecutionRequest.builder().id("one").name("lookup").arguments("{}").build();
                    assertThat(client.executeTool(request).resultText()).contains("fixture response");
                    assertThat(client.listResources()).isEmpty();
                }
            } finally {
                closed.countDown();
                http.stop(0);
            }
        }
    }

    private void subscribe(HttpExchange exchange, AtomicReference<OutputStream> eventBody, CountDownLatch close)
            throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            eventBody.set(body);
            writeEvent(body, "endpoint", "/messages");
            try {
                close.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void respond(HttpExchange exchange, OutputStream eventBody) throws IOException {
        try (exchange) {
            JsonNode request = JSON.readTree(exchange.getRequestBody());
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            ObjectNode payload = JSON.createObjectNode().put("jsonrpc", "2.0");
            payload.set("id", request.get("id"));
            ObjectNode result = payload.putObject("result");
            switch (request.path("method").asText()) {
                case "initialize" -> {
                    result.put("protocolVersion", "2024-11-05");
                    result.putObject("serverInfo").put("name", "fixture").put("version", "1");
                    var capabilities = result.putObject("capabilities");
                    capabilities.putObject("tools");
                    capabilities.putObject("resources");
                }
                case "tools/list" -> result.putArray("tools").addObject().put("name", "lookup")
                        .put("description", "测试工具").putObject("inputSchema").put("type", "object");
                case "tools/call" -> result.putArray("content").addObject().put("type", "text").put("text", "fixture response");
                case "resources/list" -> result.putArray("resources");
                default -> throw new IOException("未知测试方法");
            }
            if (eventBody != null) {
                exchange.sendResponseHeaders(202, -1);
                writeEvent(eventBody, "message", JSON.writeValueAsString(payload));
                return;
            }
            byte[] response = JSON.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private void writeEvent(OutputStream eventBody, String type, String body) throws IOException {
        eventBody.write(("event: " + type + "\ndata: " + body + "\n\n").getBytes(StandardCharsets.UTF_8));
        eventBody.flush();
    }
}
