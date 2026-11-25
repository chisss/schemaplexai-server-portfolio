package com.schemaplexai.service.memory;

import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresChatMemoryStoreTest {

    private final AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final PostgresChatMemoryStore store = new PostgresChatMemoryStore(agentExecutionMapper, chatMessageMapper);

    @BeforeEach
    void configureInsert() {
        when(chatMessageMapper.insert(any(ChatMessageEntity.class))).thenReturn(1);
    }

    @Test
    void shouldWriteMessagesAfterVersionCasSuccess() {
        AgentExecution owner = owner("exec-1", 2);
        when(agentExecutionMapper.selectOne(any())).thenReturn(owner);
        when(agentExecutionMapper.casIncrementChatVersion("exec-1", 2)).thenReturn(1);

        store.updateMessages("conv-1", List.of(UserMessage.from("你好")));

        verify(agentExecutionMapper).casIncrementChatVersion("exec-1", 2);
        verify(chatMessageMapper).delete(any());
        verify(chatMessageMapper).insert(any(ChatMessageEntity.class));
    }

    @Test
    void shouldRetryOnVersionConflictAndWrite() {
        AgentExecution owner = owner("exec-1", 2);
        AgentExecution refreshed = owner("exec-1", 3);
        when(agentExecutionMapper.selectOne(any())).thenReturn(owner);
        when(agentExecutionMapper.casIncrementChatVersion("exec-1", 2)).thenReturn(0);
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(refreshed);
        when(agentExecutionMapper.casIncrementChatVersion("exec-1", 3)).thenReturn(1);

        store.updateMessages("conv-1", List.of(UserMessage.from("你好")));

        verify(chatMessageMapper).insert(any(ChatMessageEntity.class));
        verify(agentExecutionMapper, times(2)).casIncrementChatVersion(anyString(), anyInt());
    }

    @Test
    void shouldAbandonWriteWhenVersionConflictExhausted() {
        AgentExecution owner = owner("exec-1", 2);
        when(agentExecutionMapper.selectOne(any())).thenReturn(owner);
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(owner);
        when(agentExecutionMapper.casIncrementChatVersion(anyString(), anyInt())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> store.updateMessages("conv-1", List.of(UserMessage.from("你好"))));

        verify(chatMessageMapper, never()).delete(any());
        verify(chatMessageMapper, never()).insert(any(ChatMessageEntity.class));
        verify(agentExecutionMapper, times(3)).casIncrementChatVersion(anyString(), anyInt());
    }

    @Test
    void shouldRestoreMultimodalContentAndLegacyText() {
        AgentExecution owner = owner("exec-1", 2);
        when(agentExecutionMapper.selectOne(any())).thenReturn(owner);
        when(agentExecutionMapper.casIncrementChatVersion("exec-1", 2)).thenReturn(1);
        UserMessage image = UserMessage.from(TextContent.from("描述这张图"), ImageContent.from("AQID", "image/png"));
        UserMessage legacy = UserMessage.from("旧文本");
        ToolExecutionResultMessage tool = ToolExecutionResultMessage.builder()
                .id("call-1").toolName("test").text("tool result")
                .isError(true).attributes(Map.of("activatedSkill", "demo")).build();

        store.updateMessages("conv-1", List.of(image, legacy, tool));

        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessageMapper, times(3)).insert(captor.capture());
        List<ChatMessageEntity> entities = captor.getAllValues();
        assertEquals("描述这张图", entities.getFirst().getTextContent());
        assertNotNull(entities.getFirst().getMessagePayload());
        assertFalse(entities.getFirst().toString().contains("messagePayload"));
        assertNull(entities.get(1).getMessagePayload());
        when(chatMessageMapper.selectList(any())).thenReturn(entities);
        assertEquals(List.of(image, legacy, tool), store.getMessages("conv-1"));
    }

    @Test
    void shouldFailClosedWhenInsertDoesNotWrite() {
        AgentExecution owner = owner("exec-1", 2);
        when(agentExecutionMapper.selectOne(any())).thenReturn(owner);
        when(agentExecutionMapper.casIncrementChatVersion("exec-1", 2)).thenReturn(1);
        when(chatMessageMapper.insert(any(ChatMessageEntity.class))).thenReturn(1, 0);

        assertThrows(IllegalStateException.class,
                () -> store.updateMessages("conv-1", List.of(UserMessage.from("a"), UserMessage.from("b"))));
    }

    private AgentExecution owner(String id, int version) {
        AgentExecution execution = new AgentExecution();
        execution.setId(id);
        execution.setTenantId("tenant-1");
        execution.setAgentId("agent-1");
        execution.setConversationId("conv-1");
        execution.setChatVersion(version);
        return execution;
    }
}
