package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.executor.os.CommandValidator;
import com.schemaplexai.service.agent.tool.executor.os.MacCommandAdapter;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.tool.security.ToolSecurityValidator;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BuiltinToolExecutorTest {

    @Test
    void shouldTruncateOversizedCommandOutput() throws Exception {
        BuiltinToolExecutor executor = new BuiltinToolExecutor(
                new ObjectMapper(),
                List.of(),
                mock(CommandValidator.class),
                mock(ToolExecutionLogService.class),
                mock(WorkspacePathResolver.class),
                mock(SandboxGuard.class),
                new OkHttpClient(),
                mock(ToolSecurityValidator.class)
        );
        Method method = BuiltinToolExecutor.class.getDeclaredMethod("truncateCommandOutput", String.class);
        method.setAccessible(true);

        String hugeOutput = "a".repeat(30000);
        String sanitized = (String) method.invoke(executor, hugeOutput);

        assertThat(sanitized).hasSizeLessThan(25000);
        assertThat(sanitized).contains("命令输出过长，已截断");
    }

    @Test
    void shouldFetchHtmlPageWithoutBash() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/prospect", exchange -> {
            byte[] bytes = """
                    <html>
                    <head><title>South America Amino Leads</title></head>
                    <body>
                    <main>
                    <h1>Lead List</h1>
                    <p>Max Titanium keeps BCAA and glutamine product pages online.</p>
                    <p>Email: sales@amino.example.com</p>
                    <p>Phone: +55 11 4000-1234</p>
                    <a href="/contato">Contato</a>
                    <a href="/produtos/bcaa">BCAA</a>
                    <a href="https://instagram.com/amino-brand">Instagram</a>
                    </main>
                    </body>
                    </html>
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            BuiltinToolExecutor executor = new BuiltinToolExecutor(
                    objectMapper,
                    List.of(new MacCommandAdapter()),
                    new CommandValidator(),
                    mock(ToolExecutionLogService.class),
                    mock(WorkspacePathResolver.class),
                    mock(SandboxGuard.class),
                    new OkHttpClient(),
                    mock(ToolSecurityValidator.class)
            );
            ToolCall toolCall = ToolCall.builder()
                    .callId("call-web-fetch")
                    .toolCode("web.fetch")
                    .arguments(objectMapper.valueToTree(Map.of(
                            "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/prospect",
                            "maxChars", 500
                    )))
                    .build();
            AgentToolBinding binding = new AgentToolBinding();
            binding.setToolCode("web.fetch");
            binding.setSourceType("builtin");

            var result = executor.execute("tenant-test", "agent-test", binding, toolCall);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getResult().get("title").asText()).isEqualTo("South America Amino Leads");
            assertThat(result.getResult().get("content").asText()).contains("Max Titanium keeps BCAA");
            assertThat(result.getResult().get("finalUrl").asText()).contains("/prospect");
            assertThat(result.getResult().get("emails").get(0).asText()).isEqualTo("sales@amino.example.com");
            assertThat(result.getResult().get("phones").get(0).asText()).contains("+55 11 4000-1234");
            assertThat(result.getResult().get("links")).hasSize(2);
            assertThat(result.getResult().get("links").get(0).get("url").asText())
                    .containsAnyOf("/contato", "/produtos/bcaa");
        } finally {
            server.stop(0);
        }
    }
}
