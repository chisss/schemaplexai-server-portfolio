package com.schemaplexai.service.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@Slf4j
@EnabledIfSystemProperty(named = "agent.memory.writeBenchmark", matches = "true")
class AgentMemoryWriteBenchmarkTest {

    private static final String CONVERSATION = "tenant-1:benchmark";
    private static final String EXECUTION = "execution-1";
    private static final int ROUNDS = 24;
    private static final int WINDOW = 32;
    private static final int MESSAGE_COUNT = 1 + ROUNDS * 3 + 1;

    @Test
    void comparePersistenceOperationsForTheSameFixedToolLoop() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "benchmark"),
                ChatMessageEntity.class);
        StorageFixture baseline = storageFixture();
        StorageFixture appendOnly = storageFixture();

        ChatMemory oldWindow = window().chatMemoryStore(baseline.store()).build();
        ChatMemory newWindow = new ArchivedChatMemory(
                CONVERSATION, EXECUTION, appendOnly.store(), window().build(), WINDOW);
        replayToolLoop(oldWindow);
        replayToolLoop(newWindow);

        StorageCounts oldCounts = baseline.counts();
        StorageCounts newCounts = appendOnly.counts();
        log.info("Agent 持久化计数，模拟 Mapper，轮次={}，消息数={}，窗口上限={}，旧路径={}，追加路径={}",
                ROUNDS, MESSAGE_COUNT, WINDOW, oldCounts, newCounts);

        assertThat(oldWindow.messages()).isEqualTo(newWindow.messages());
        assertThat(oldCounts.messageInserts()).isGreaterThan(MESSAGE_COUNT);
        assertThat(oldCounts.messageDeletes()).isEqualTo(MESSAGE_COUNT);
        assertThat(newCounts.messageInserts()).isEqualTo(MESSAGE_COUNT);
        assertThat(newCounts.messageDeletes()).isZero();
        assertThat(newCounts.totalDatabaseCalls()).isLessThan(oldCounts.totalDatabaseCalls());
    }

    private void replayToolLoop(ChatMemory memory) {
        memory.add(UserMessage.from("汇总这 24 个模拟批次的结果"));
        for (int round = 1; round <= ROUNDS; round++) {
            var first = toolRequest("first-" + round);
            var second = toolRequest("second-" + round);
            memory.add(AiMessage.from(List.of(first, second)));
            memory.add(ToolExecutionResultMessage.from(first, "模拟结果甲"));
            memory.add(ToolExecutionResultMessage.from(second, "模拟结果乙"));
            memory.messages();
        }
        memory.add(AiMessage.from("模拟结果汇总完成"));
    }

    private ToolExecutionRequest toolRequest(String id) {
        return ToolExecutionRequest.builder().id(id).name("read_fixture").arguments("{}").build();
    }

    private TokenWindowChatMemory.Builder window() {
        return TokenWindowChatMemory.builder().id(CONVERSATION).maxTokens(WINDOW, new TokenCountEstimator() {
            @Override
            public int estimateTokenCountInText(String text) {
                return 1;
            }

            @Override
            public int estimateTokenCountInMessage(ChatMessage message) {
                return 1;
            }

            @Override
            public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
                int count = 0;
                for (ChatMessage ignored : messages) {
                    count++;
                }
                return count;
            }
        });
    }

    private StorageFixture storageFixture() {
        AgentExecutionMapper executions = mock(AgentExecutionMapper.class);
        ChatMessageMapper messages = mock(ChatMessageMapper.class);
        RedisChatMemoryStore redis = mock(RedisChatMemoryStore.class);
        AgentExecution owner = new AgentExecution();
        owner.setId(EXECUTION);
        owner.setTenantId("tenant-1");
        owner.setAgentId("agent-1");
        owner.setConversationId(CONVERSATION);
        owner.setChatVersion(0);
        when(executions.selectOne(any())).thenReturn(owner);
        when(executions.selectById(EXECUTION)).thenReturn(owner);
        when(executions.casIncrementChatVersion(EXECUTION, 0)).thenReturn(1);
        AtomicInteger nextIndex = new AtomicInteger();
        ChatMessageEntity currentTurn = new ChatMessageEntity();
        currentTurn.setTurnIndex(1);
        when(messages.selectOne(any())).thenAnswer(invocation -> nextIndex.get() == 0 ? null : currentTurn);
        when(messages.nextTurnIndex(CONVERSATION)).thenReturn(1);
        when(messages.nextMessageIndex(CONVERSATION)).thenAnswer(invocation -> nextIndex.get());
        when(messages.insert(any(ChatMessageEntity.class))).thenAnswer(invocation -> {
            nextIndex.incrementAndGet();
            return 1;
        });
        AtomicReference<List<ChatMessage>> cached = new AtomicReference<>();
        when(redis.getMessages(CONVERSATION)).thenAnswer(invocation -> cached.get());
        doAnswer(invocation -> {
            cached.set(List.copyOf(invocation.getArgument(1)));
            return null;
        }).when(redis).updateMessages(any(), any());
        return new StorageFixture(new CompositeChatMemoryStore(new PostgresChatMemoryStore(executions, messages), redis),
                executions, messages, redis);
    }

    private record StorageFixture(CompositeChatMemoryStore store, AgentExecutionMapper executions,
                                  ChatMessageMapper messages, RedisChatMemoryStore redis) {
        StorageCounts counts() {
            return new StorageCounts(count(messages, "insert"), count(messages, "delete"),
                    count(executions, "casIncrementChatVersion"),
                    mockingDetails(executions).getInvocations().size() + mockingDetails(messages).getInvocations().size(),
                    count(redis, "updateMessages"));
        }

        private static long count(Object mapper, String method) {
            return mockingDetails(mapper).getInvocations().stream()
                    .filter(invocation -> method.equals(invocation.getMethod().getName())).count();
        }
    }

    private record StorageCounts(long messageInserts, long messageDeletes, long versionUpdates,
                                 long totalDatabaseCalls, long redisUpdates) {
    }
}
