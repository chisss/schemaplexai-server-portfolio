package com.schemaplexai.service.memory;

import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.ChatMessageEntity;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostgresChatMemoryStoreTest {

    @Test
    void shouldRestoreAiToolCallsFromStoredChatMessages() {
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        PostgresChatMemoryStore store = new PostgresChatMemoryStore(agentExecutionMapper, chatMessageMapper);

        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId("conversation-1");
        entity.setMessageType("AI");
        entity.setTextContent("");
        entity.setToolCalls(List.of(
                Map.of("id", "call-1", "name", "sys.read", "arguments", "{\"path\":\"a\"}"),
                Map.of("id", "call-2", "name", "sys.ls", "arguments", "{\"path\":\"b\"}")
        ));
        when(chatMessageMapper.selectList(any())).thenReturn(List.of(entity));

        List<ChatMessage> messages = store.getMessages("conversation-1");

        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst()).isInstanceOf(AiMessage.class);
        AiMessage aiMessage = (AiMessage) messages.getFirst();
        assertThat(aiMessage.hasToolExecutionRequests()).isTrue();
        assertThat(aiMessage.toolExecutionRequests())
                .extracting(request -> request.name())
                .containsExactly("sys.read", "sys.ls");
        assertThat(aiMessage.toolExecutionRequests())
                .extracting(request -> request.id())
                .containsExactly("call-1", "call-2");
    }
}
