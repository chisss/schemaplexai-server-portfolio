package com.schemaplexai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.entity.AiModel;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.RetriableException;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jModelFactoryTest {

    @ParameterizedTest
    @ValueSource(strings = {"openai", "anthropic"})
    void shouldLeaveRetriesToTheExecutionEngine(String protocol) throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        try {
            AiModel model = buildModel("fixture", "retry-fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Map.of("protocol", protocol));
            var config = AiModelConfig.from(model);
            var factory = new LangChain4jModelFactory();
            var executionModel = factory.getOrCreateForExecution(config);
            var request = ChatRequest.builder().messages(UserMessage.from("计量重试")).build();
            assertThat(executionModel).isSameAs(factory.getOrCreateForExecution(config));
            assertThat(executionModel).isNotSameAs(factory.getOrCreate(config));
            assertThatThrownBy(() -> executionModel.chat(request)).isInstanceOf(RetriableException.class);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldUseConfiguredTimeoutWhenBuildingModel() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModelConfig config = AiModelConfig.builder()
                .timeoutSeconds(180)
                .build();

        Duration timeout = factory.resolveTimeout(config);

        assertThat(timeout).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void shouldFallbackToDefaultTimeoutWhenConfigMissing() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();

        Duration timeout = factory.resolveTimeout(AiModelConfig.builder().build());

        assertThat(timeout).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldUseAnthropicMessagesProtocolForGlmProxy() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModel model = buildModel("Anthropic", "glm-4.7", "https://open.bigmodel.cn/api/anthropic", null);

        AiModelConfig config = AiModelConfig.from(model);
        ChatModel chatModel = factory.getOrCreate(config);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_ANTHROPIC);
        assertThat(config.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/anthropic/v1");
        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void shouldUseOpenAiProtocolForClaudeProxy() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModel model = buildModel("Anthropic", "claude-sonnet-4-6", "https://code.newcli.com/claude", null);

        AiModelConfig config = AiModelConfig.from(model);
        ChatModel chatModel = factory.getOrCreate(config);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_OPENAI);
        assertThat(config.getBaseUrl()).isEqualTo("https://code.newcli.com/claude/v1");
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatRequestParameters requestParameters = ((OpenAiChatModel) chatModel).defaultRequestParameters();
        assertThat(requestParameters.maxOutputTokens()).isEqualTo(256);
        assertThat(requestParameters.maxCompletionTokens()).isNull();
    }

    @Test
    void shouldRespectExplicitProtocolOverride() {
        AiModel model = buildModel(
                "Anthropic",
                "claude-sonnet-4-6",
                "https://open.bigmodel.cn/api/anthropic",
                Map.of("protocol", "openai")
        );

        AiModelConfig config = AiModelConfig.from(model);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_OPENAI);
        assertThat(config.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/anthropic/v1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI", "Anthropic"})
    void shouldRoundTripToolCallsThroughConfiguredHttpClient(String provider) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<JsonNode> capturedRequest = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequest.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = """
                    {"id":"test-response","object":"chat.completion","model":"test-agent",
                     "choices":[{"index":0,"message":{"role":"assistant","content":null,
                       "tool_calls":[{"id":"call-1","type":"function",
                         "function":{"name":"inspect_workspace","arguments":"{}"}}]},
                       "finish_reason":"tool_calls"}],
                     "usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            AiModel model = buildModel(provider, "test-agent",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    Map.of("protocol", "openai"));
            ChatModel chatModel = new LangChain4jModelFactory().getOrCreate(AiModelConfig.from(model));

            var response = chatModel.chat(ChatRequest.builder()
                    .messages(UserMessage.from("检查工作空间"))
                    .toolSpecifications(ToolSpecification.builder().name("inspect_workspace").build())
                    .build());

            assertThat(capturedRequest.get().path("model").asText()).isEqualTo("test-agent");
            assertThat(capturedRequest.get().path("tools").get(0).path("function").path("name").asText())
                    .isEqualTo("inspect_workspace");
            assertThat(response.aiMessage().toolExecutionRequests()).singleElement().satisfies(tool -> {
                assertThat(tool.id()).isEqualTo("call-1");
                assertThat(tool.name()).isEqualTo("inspect_workspace");
                assertThat(tool.arguments()).isEqualTo("{}");
            });
            assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(20);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRoundTripAnthropicMessagesWithToolResult() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var lastRequest = new AtomicReference<JsonNode>();
        var mapper = new ObjectMapper();
        server.createContext("/v1/messages", exchange -> {
            try (exchange) {
                JsonNode request = mapper.readTree(exchange.getRequestBody());
                lastRequest.set(request);
                boolean toolResultReceived = request.path("messages").size() > 1;
                String content = toolResultReceived
                        ? "[{\"type\":\"text\",\"text\":\"读取成功\"}]"
                        : "[{\"type\":\"tool_use\",\"id\":\"toolu_fixture\",\"name\":\"lookup\",\"input\":{}}]";
                byte[] body = """
                        {"id":"msg_fixture","type":"message","role":"assistant","model":"claude-fixture",
                         "content":%s,"stop_reason":"%s","stop_sequence":null,
                         "usage":{"input_tokens":12,"output_tokens":8}}
                        """.formatted(content, toolResultReceived ? "end_turn" : "tool_use")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            AiModel config = buildModel("Anthropic", "claude-fixture",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Map.of("protocol", "anthropic"));
            ChatModel chat = new LangChain4jModelFactory().getOrCreate(AiModelConfig.from(config));
            var user = UserMessage.from("读取测试记录");
            var tool = ToolSpecification.builder().name("lookup").build();
            var first = chat.chat(ChatRequest.builder().messages(user).toolSpecifications(tool).build());
            assertThat(first.aiMessage().toolExecutionRequests()).singleElement().satisfies(call -> {
                assertThat(call.id()).isEqualTo("toolu_fixture");
                assertThat(call.name()).isEqualTo("lookup");
            });
            assertThat(lastRequest.get().path("tools").get(0).path("name").asText()).isEqualTo("lookup");

            var result = ToolExecutionResultMessage.from(first.aiMessage().toolExecutionRequests().getFirst(), "测试结果");
            var second = chat.chat(ChatRequest.builder().messages(user, first.aiMessage(), result)
                    .toolSpecifications(tool).build());

            assertThat(lastRequest.get().path("messages").get(2).path("content").get(0).path("tool_use_id").asText())
                    .isEqualTo("toolu_fixture");
            assertThat(second.aiMessage().text()).isEqualTo("读取成功");
            assertThat(second.tokenUsage().totalTokenCount()).isEqualTo(20);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OpenAI", "Anthropic"})
    void shouldStreamModelProgressAndAssembleToolCallFromHttpEvents(String provider) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var capturedRequest = new AtomicReference<JsonNode>();
        server.createContext("/v1/chat/completions", exchange -> {
            capturedRequest.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] response = """
                    data: {"id":"response","choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"model progress"}}]}

                    data: {"id":"response","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"inspect_workspace","arguments":"{"}}]}}]}

                    data: {"id":"response","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}

                    data: {"id":"response","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                    data: {"id":"response","choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            AiModel model = buildModel(provider, "test-agent",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", Map.of("protocol", "openai"));
            model.setSupportedReasoningEfforts("medium");
            var streaming = new LangChain4jModelFactory().getOrCreateStreaming(AiModelConfig.from(model, "medium"));
            var completion = new CompletableFuture<ChatResponse>();
            var thinking = new AtomicReference<String>();
            streaming.chat(ChatRequest.builder().messages(UserMessage.from("检查工作空间"))
                    .toolSpecifications(ToolSpecification.builder().name("inspect_workspace").build()).build(),
                    new StreamingChatResponseHandler() {
                        @Override
                        public void onPartialThinking(PartialThinking partial) {
                            thinking.set(partial.text());
                        }

                        @Override
                        public void onCompleteResponse(ChatResponse response) {
                            completion.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            completion.completeExceptionally(error);
                        }
                    });
            var response = completion.get(5, TimeUnit.SECONDS);
            assertThat(capturedRequest.get().path("stream").asBoolean()).isTrue();
            assertThat(thinking.get()).isEqualTo("model progress");
            assertThat(response.aiMessage().toolExecutionRequests()).singleElement().satisfies(tool -> {
                assertThat(tool.id()).isEqualTo("call-1");
                assertThat(tool.name()).isEqualTo("inspect_workspace");
                assertThat(tool.arguments()).isEqualTo("{}");
            });
            assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(20);
        } finally {
            server.stop(0);
        }
    }

    private AiModel buildModel(String provider, String modelId, String baseUrl, Map<String, Object> defaultParams) {
        AiModel model = new AiModel();
        model.setProvider(provider);
        model.setModelId(modelId);
        model.setBaseUrl(baseUrl);
        model.setDefaultParams(defaultParams);
        model.setApiKeyEncrypted(Base64.getEncoder().encodeToString("test-key".getBytes(StandardCharsets.UTF_8)));
        model.setTimeoutSeconds(60);
        model.setMaxTokens(256);
        return model;
    }
}
