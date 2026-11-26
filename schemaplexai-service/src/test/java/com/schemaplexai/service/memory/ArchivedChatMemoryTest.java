package com.schemaplexai.service.memory;

import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ArchivedChatMemoryTest {

    @Test
    void privateExecutionReplaysOnlyItsOwnMessages() {
        CompositeChatMemoryStore store = mock(CompositeChatMemoryStore.class);
        AiMessage ownMessage = AiMessage.from("私有执行的本次摘要");
        when(store.loadExecutionWindow("conversation", "current", 50)).thenReturn(List.of(ownMessage));

        ArchivedChatMemory memory = new ArchivedChatMemory("conversation", "current", store,
                MessageWindowChatMemory.withMaxMessages(8), 50, true);

        assertThat(memory.messages()).containsExactly(ownMessage);
        verify(store).loadExecutionWindow("conversation", "current", 50);
        verifyNoMoreInteractions(store);
    }

    @Test
    void shouldLoadOnceKeepRawInputAndBatchOriginalMessagesWithoutWritingProjection() {
        CompositeChatMemoryStore store = mock(CompositeChatMemoryStore.class);
        UserMessage previous = UserMessage.from("上一轮");
        when(store.loadWindow("conversation", "current", 50)).thenReturn(List.of(previous));
        ArchivedChatMemory memory = new ArchivedChatMemory("conversation", "current", store,
                MessageWindowChatMemory.withMaxMessages(8), 50);
        UserMessage input = UserMessage.from("用户原话\n附件内容");
        AiMessage answer = AiMessage.from("模型结果");
        UserMessage runtimePrompt = UserMessage.from("系统生成的续写提示");

        memory.addUserInput(input, "用户原话");
        memory.add(List.of(answer, runtimePrompt));
        assertThat(memory.messages()).containsExactly(previous, input, answer, runtimePrompt);
        assertThat(memory.messages()).isSameAs(memory.messages());
        memory.set(List.of(UserMessage.from("压缩摘要")));

        assertThat(memory.messages()).containsExactly(UserMessage.from("压缩摘要"));
        verify(store).loadWindow("conversation", "current", 50);
        verify(store).appendMessages("conversation", "current", List.of(new ArchivedMessage(input, "用户原话")));
        verify(store).appendMessages("conversation", "current",
                List.of(new ArchivedMessage(answer, null), new ArchivedMessage(runtimePrompt, null)));
        verifyNoMoreInteractions(store);
    }

    @Test
    void shouldRecordResumedResultsWithoutMutatingTheRebuiltProjection() {
        CompositeChatMemoryStore store = mock(CompositeChatMemoryStore.class);
        ArchivedChatMemory memory = new ArchivedChatMemory("conversation", "current", store,
                MessageWindowChatMemory.withMaxMessages(8), 50);
        ToolExecutionResultMessage result = ToolExecutionResultMessage.from("call", "tool", "output");

        memory.recordResumedToolResults(List.of(result));

        verify(store).appendMissingToolResults("conversation", "current", List.of(result));
        assertThat(memory.messages()).isEmpty();
    }

    @Test
    void shouldInjectCommittedUserInputWithoutASecondArchiveWrite() {
        CompositeChatMemoryStore store = mock(CompositeChatMemoryStore.class);
        ArchivedChatMemory memory = new ArchivedChatMemory("conversation", "current", store,
                MessageWindowChatMemory.withMaxMessages(8), 50);
        UserMessage input = UserMessage.from("已归档的引导");

        memory.addArchivedUserInput(input);

        assertThat(memory.messages()).containsExactly(input);
        verify(store).loadWindow("conversation", "current", 50);
        verifyNoMoreInteractions(store);
    }
}
