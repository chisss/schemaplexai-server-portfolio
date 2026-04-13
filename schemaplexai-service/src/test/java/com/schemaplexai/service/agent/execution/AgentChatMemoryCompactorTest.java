package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
