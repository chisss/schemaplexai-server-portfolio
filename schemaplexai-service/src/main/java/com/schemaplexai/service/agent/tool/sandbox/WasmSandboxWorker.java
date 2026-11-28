package com.schemaplexai.service.agent.tool.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 独立容器入口，只接收有界代码和返回执行结果。 */
public final class WasmSandboxWorker {
    private static final int MAX_REQUEST_BYTES = 325_000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final WasmSandboxService sandbox = new WasmSandboxService(new CodeExecProperties());

    public static void start() throws IOException {
        var worker = new WasmSandboxWorker();
        var server = HttpServer.create(new InetSocketAddress(8080), 32);
        server.setExecutor(new ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32)));
        server.createContext("/health", exchange -> worker.health(exchange));
        server.createContext("/execute", worker::execute);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(1), "wasm-worker-shutdown"));
        server.start();
    }

    private void health(HttpExchange exchange) throws IOException {
        try (exchange) {
            send(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        }
    }

    private void execute(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"POST".equals(exchange.getRequestMethod()) || !"/execute".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
            if (body.length > MAX_REQUEST_BYTES) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            CodeExecRequest request;
            try {
                request = mapper.readValue(body, CodeExecRequest.class);
            } catch (IOException invalid) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (request == null) {
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            send(exchange, 200, mapper.writeValueAsBytes(sandbox.execute(request)));
        }
    }

    private void send(HttpExchange exchange, int status, byte[] data) throws IOException {
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
    }
}
