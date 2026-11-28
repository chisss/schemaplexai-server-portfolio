package com.schemaplexai.service.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CompositeChatMemoryStoreTest {

    private final PostgresChatMemoryStore postgres = mock(PostgresChatMemoryStore.class);
    private final RedisChatMemoryStore redis = mock(RedisChatMemoryStore.class);
    private final CompositeChatMemoryStore store = new CompositeChatMemoryStore(postgres, redis);
    private final List<ChatMessage> messages = List.of(UserMessage.from("图像待分析"));

    @Test
    void shouldInvalidateCacheWhenPostgresWriteFails() {
        doThrow(new IllegalStateException("写入失败")).when(postgres).updateMessages("conv", messages);

        assertThrows(IllegalStateException.class, () -> store.updateMessages("conv", messages));

        verify(redis).deleteMessages("conv");
    }

    @Test
    void shouldOnlyPublishToCacheAfterTransactionCommits() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(postgres.getMessages("conv")).thenReturn(messages);
            store.updateMessages("conv", messages);
            assertEquals(messages, store.getMessages("conv"));
            verifyNoInteractions(redis);

            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

            verify(redis).updateMessages("conv", messages);
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
