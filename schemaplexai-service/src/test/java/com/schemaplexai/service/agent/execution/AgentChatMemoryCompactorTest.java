package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentChatMemoryCompactorTest {

    @Test
    void shouldKeepToolInteractionChainIntactAfterCompaction() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                UserMessage.from("原始需求说明"),
                AiMessage.from("先检查目录结构"),
                ToolExecutionResultMessage.from("call-old-1", "sys.ls", largeToolResult("module-a")),
                AiMessage.from("继续查看 Controller"),
                ToolExecutionResultMessage.from("call-old-2", "sys.grep", largeToolResult("controller-a")),
                AiMessage.from("我来继续列目录"),
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("call-1").name("sys.ls").arguments("{\"path\":\"a\"}").build(),
                        ToolExecutionRequest.builder().id("call-2").name("sys.ls").arguments("{\"path\":\"b\"}").build(),
                        ToolExecutionRequest.builder().id("call-3").name("sys.ls").arguments("{\"path\":\"c\"}").build(),
                        ToolExecutionRequest.builder().id("call-4").name("sys.ls").arguments("{\"path\":\"d\"}").build(),
                        ToolExecutionRequest.builder().id("call-5").name("sys.ls").arguments("{\"path\":\"e\"}").build()
                )),
                ToolExecutionResultMessage.from("call-1", "sys.ls", largeToolResult("recent-a")),
                ToolExecutionResultMessage.from("call-2", "sys.ls", largeToolResult("recent-b")),
                ToolExecutionResultMessage.from("call-3", "sys.ls", largeToolResult("recent-c")),
                ToolExecutionResultMessage.from("call-4", "sys.ls", largeToolResult("recent-d")),
                ToolExecutionResultMessage.from("call-5", "sys.ls", largeToolResult("recent-e"))
        ));

        AgentChatMemoryCompactor.CompactionResult result =
                compactor.compactIfNeeded(chatMemory, null, buildModelConfig());

        assertThat(result.compacted()).isTrue();
        assertThat(chatMemory.messages()).hasSize(7);
        assertThat(chatMemory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatMemory.messages().getFirst()).singleText()).startsWith("[历史摘要]");
        assertThat(chatMemory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatMemory.messages().get(1)).hasToolExecutionRequests()).isTrue();
        assertThat(((AiMessage) chatMemory.messages().get(1)).toolExecutionRequests()).hasSize(5);
        assertThat(chatMemory.messages().subList(2, chatMemory.messages().size()))
                .allMatch(message -> message instanceof ToolExecutionResultMessage);
    }

    @Test
    void shouldReuseHistoricalSummaryWhenSummaryAlreadyStoredAsUserMessage() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                UserMessage.from("[历史摘要]\n目标:\n- 已确认旧历史\n关键事实:\n- 旧事实\n关键决定:\n- 旧决定\n引用:\n- 旧引用"),
                UserMessage.from("继续补充需求分析"),
                AiMessage.from("先检查目录"),
                ToolExecutionResultMessage.from("call-1", "sys.ls", largeToolResult("recent-a")),
                AiMessage.from("继续读取文件"),
                ToolExecutionResultMessage.from("call-2", "sys.read", largeToolResult("recent-b")),
                AiMessage.from("准备再列目录"),
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("call-3").name("sys.ls").arguments("{\"path\":\"x\"}").build()
                )),
                ToolExecutionResultMessage.from("call-3", "sys.ls", largeToolResult("recent-c"))
        ));

        compactor.compactIfNeeded(chatMemory, null, buildModelConfig());

        assertThat(chatMemory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatMemory.messages().getFirst()).singleText()).contains("旧事实");
    }

    @Test
    void shouldKeepCompleteToolExchangeDuringRequestNormalization() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                UserMessage.from("需求上下文"),
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{\"path\":\"a\"}").build(),
                        ToolExecutionRequest.builder().id("call-2").name("sys.ls").arguments("{\"path\":\"b\"}").build()
                )),
                ToolExecutionResultMessage.from("call-1", "sys.read", largeToolResult("recent-a")),
                ToolExecutionResultMessage.from("call-2", "sys.ls", largeToolResult("recent-b"))
        ));

        AgentChatMemoryCompactor.NormalizationResult result = compactor.normalizeForRequest(chatMemory);

        assertThat(result.normalized()).isFalse();
        assertThat(chatMemory.messages()).hasSize(4);
        assertThat(chatMemory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatMemory.messages().get(1)).hasToolExecutionRequests()).isTrue();
        assertThat(chatMemory.messages().subList(2, 4))
                .allMatch(message -> message instanceof ToolExecutionResultMessage);
    }

    @Test
    void shouldConvertIncompleteToolExchangeIntoSummaryBeforeRequest() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                UserMessage.from("需求上下文"),
                AiMessage.from(List.of(
                        ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{\"path\":\"a\"}").build(),
                        ToolExecutionRequest.builder().id("call-2").name("sys.read").arguments("{\"path\":\"b\"}").build()
                )),
                ToolExecutionResultMessage.from("call-1", "sys.read", largeToolResult("recent-a"))
        ));

        AgentChatMemoryCompactor.NormalizationResult result = compactor.normalizeForRequest(chatMemory);

        assertThat(result.normalized()).isTrue();
        assertThat(result.convertedSegments()).isEqualTo(1);
        assertThat(chatMemory.messages()).hasSize(2);
        assertThat(chatMemory.messages().get(1)).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatMemory.messages().get(1)).singleText())
                .contains("[历史工具摘要]")
                .contains("sys.read");
        assertThat(chatMemory.messages())
                .noneMatch(message -> message instanceof ToolExecutionResultMessage);
        assertThat(chatMemory.messages())
                .noneMatch(message -> message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests());
    }

    @Test
    void shouldConvertLeadingOrphanToolResultsIntoSummaryBeforeRequest() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                ToolExecutionResultMessage.from("call-1", "sys.read", largeToolResult("orphan-a")),
                ToolExecutionResultMessage.from("call-2", "sys.ls", largeToolResult("orphan-b")),
                AiMessage.from("后续正常结论")
        ));

        AgentChatMemoryCompactor.NormalizationResult result = compactor.normalizeForRequest(chatMemory);

        assertThat(result.normalized()).isTrue();
        assertThat(result.convertedSegments()).isEqualTo(1);
        assertThat(chatMemory.messages()).hasSize(2);
        assertThat(chatMemory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(((UserMessage) chatMemory.messages().getFirst()).singleText())
                .contains("[历史工具摘要]")
                .contains("sys.read")
                .contains("sys.ls");
        assertThat(chatMemory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatMemory.messages().get(1)).text()).isEqualTo("后续正常结论");
    }

    @Test
    void shouldDropEmptyAiMessagesWithoutToolRequestsBeforeRequest() {
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport());
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(List.of(
                UserMessage.from("需求上下文"),
                AiMessage.from(""),
                AiMessage.from("   "),
                AiMessage.from("保留的正常结论")
        ));

        AgentChatMemoryCompactor.NormalizationResult result = compactor.normalizeForRequest(chatMemory);

        assertThat(result.normalized()).isTrue();
        assertThat(result.droppedMessages()).isEqualTo(2);
        assertThat(chatMemory.messages()).hasSize(2);
        assertThat(chatMemory.messages().getFirst()).isInstanceOf(UserMessage.class);
        assertThat(chatMemory.messages().get(1)).isInstanceOf(AiMessage.class);
        assertThat(((AiMessage) chatMemory.messages().get(1)).text()).isEqualTo("保留的正常结论");
    }

    @Test
    void shouldSummarizeWithConstructorInjectedModel() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(List.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("目标: 压缩测试目标\n已完成: 无\n决策: 无\n待处理: 无"))
                .build());
        AgentChatMemoryCompactor compactor = new AgentChatMemoryCompactor(new TokenEstimatorSupport(), model);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("压缩前需求说明"));
        for (int index = 0; index < 4; index++) {
            messages.add(AiMessage.from("执行步骤 " + index));
            messages.add(ToolExecutionResultMessage.from("call-" + index, "sys.ls", largeToolResult("module-" + index)));
        }
        InMemoryChatMemory chatMemory = new InMemoryChatMemory(messages);

        AgentChatMemoryCompactor.CompactionResult result =
                compactor.compactIfNeeded(chatMemory, null, buildModelConfig());

        assertThat(result.compacted()).isTrue();
        String summary = ((UserMessage) chatMemory.messages().getFirst()).singleText();
        assertThat(summary).contains("[历史摘要]").contains("压缩测试目标");
    }

    private AiModelConfig buildModelConfig() {
        return AiModelConfig.builder()
                .modelId("glm-4.7")
                .maxTokens(2_048)
                .contextWindowTokens(6_000)
                .build();
    }

    private static String largeToolResult(String label) {
        return label + ":" + "x".repeat(1_500);
    }

    private static final class InMemoryChatMemory implements ChatMemory {

        private final List<ChatMessage> messages;

        private InMemoryChatMemory(List<ChatMessage> initialMessages) {
            this.messages = new ArrayList<>(initialMessages);
        }

        @Override
        public Object id() {
            return "test-memory";
        }

        @Override
        public void add(ChatMessage message) {
            messages.add(message);
        }

        @Override
        public List<ChatMessage> messages() {
            return messages;
        }

        @Override
        public void clear() {
            messages.clear();
        }

        @Override
        public void set(Iterable<ChatMessage> messages) {
            this.messages.clear();
            messages.forEach(this.messages::add);
        }
    }
}
