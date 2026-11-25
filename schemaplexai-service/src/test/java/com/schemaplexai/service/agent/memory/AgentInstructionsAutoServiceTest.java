package com.schemaplexai.service.agent.memory;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.AgentMemory;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentInstructionsAutoServiceTest {

    private final ContextItemMapper items = mock(ContextItemMapper.class);
    private final AgentContextBindingMapper bindings = mock(AgentContextBindingMapper.class);
    private final AgentMemoryExtractionService memories = mock(AgentMemoryExtractionService.class);
    private final AIModelRouter router = mock(AIModelRouter.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final AgentInstructionsAutoService service = new AgentInstructionsAutoService(
            mock(ContextEntityMapper.class), items, bindings, memories, router);
    private final ContextItem item = new ContextItem();

    @BeforeAll
    static void initializeMapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ContextItem.class);
    }

    @BeforeEach
    void setUp() {
        var binding = new AgentContextBinding();
        binding.setContextId("context");
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        item.setId("instructions");
        item.setTitle("Agent 专属指令（自动生成）");
        item.setContent("旧内容");
        item.setMetadata(Map.of("custom", "preserved"));
        item.setUpdatedAt(LocalDateTime.of(2026, 11, 24, 9, 0));
        when(items.selectOne(any())).thenReturn(item);
        when(memories.getActiveMemories("tenant", "agent", 30))
                .thenReturn(List.of(memory("使用 Java 17"), memory("运行测试"), memory("记录构建结果")));
        when(router.resolveCompactModel("tenant")).thenReturn(model("model-a"));
        when(router.buildChatModel(any())).thenReturn(chatModel);
        when(chatModel.chat(anyList())).thenReturn(response("新指令"));
        when(items.update(any(ContextItem.class), any())).thenAnswer(call -> {
            ContextItem update = call.getArgument(0);
            item.setContent(update.getContent());
            item.setMetadata(update.getMetadata());
            return 1;
        });
    }

    @Test
    void skipsModelAndWriteForSameMemoriesInDifferentTieOrder() {
        runCompletion();
        when(memories.getActiveMemories("tenant", "agent", 30))
                .thenReturn(List.of(memory("运行测试"), memory("记录构建结果"), memory("使用 Java 17")));
        runCompletion();

        verify(chatModel, times(1)).chat(anyList());
        verify(items, times(1)).update(any(ContextItem.class), any());
        assertEquals("preserved", item.getMetadata().get("custom"));
        assertTrue(AgentInstructionsAutoService.isMemoryDerivedInstruction(item));
    }

    @Test
    void regeneratesWhenMemoryOrModelChanges() {
        runCompletion();
        when(memories.getActiveMemories("tenant", "agent", 30))
                .thenReturn(List.of(memory("使用 Java 21"), memory("运行测试"), memory("记录构建结果")));
        runCompletion();
        when(router.resolveCompactModel("tenant")).thenReturn(model("model-b"));
        runCompletion();

        verify(chatModel, times(3)).chat(anyList());
    }

    @Test
    void failedOrEmptyResponseLeavesWorkRetryable() {
        when(chatModel.chat(anyList()))
                .thenThrow(new IllegalStateException())
                .thenReturn(response(" "))
                .thenReturn(response("成功"));

        runCompletion();
        runCompletion();
        runCompletion();
        runCompletion();

        assertEquals("成功", item.getContent());
        verify(chatModel, times(3)).chat(anyList());
        verify(items, times(1)).update(any(ContextItem.class), any());
    }

    @Test
    void preservesUserAuthoredAndManuallyRevisedInstructions() {
        item.setTitle("人工维护指令");
        runCompletion();
        verify(router, never()).resolveCompactModel(anyString());
        item.setTitle("Agent 专属指令（自动生成）");
        runCompletion();
        item.setContent("人工编辑后的指令");
        runCompletion();

        assertEquals("人工编辑后的指令", item.getContent());
        verify(chatModel, times(1)).chat(anyList());
    }

    @Test
    void concurrentCompletionDeduplicatesAndDatabaseUpdateChecksObservedTimestamp() {
        LocalDateTime observedAt = item.getUpdatedAt();
        when(chatModel.chat(anyList())).thenAnswer(call -> {
            service.updateFromMemoriesAsync("tenant", "agent", "overlapping");
            item.setContent("并发修改");
            item.setUpdatedAt(observedAt.plusSeconds(1));
            return response("过时的生成结果");
        });
        when(items.update(any(ContextItem.class), any())).thenReturn(0);

        runCompletion();

        assertEquals("并发修改", item.getContent());
        verify(chatModel, times(1)).chat(anyList());
        verify(items).update(any(ContextItem.class), argThat(wrapper -> {
            var expected = (LambdaUpdateWrapper<ContextItem>) wrapper;
            return expected.getSqlSegment().contains("updated_at")
                    && expected.getParamNameValuePairs().containsValue(observedAt);
        }));
    }

    private void runCompletion() {
        service.updateFromMemoriesAsync("tenant", "agent", "execution");
    }

    private static AiModelConfig model(String modelId) {
        return AiModelConfig.builder().modelId(modelId).protocol("openai").provider("deepseek").build();
    }

    private static AgentMemory memory(String content) {
        var memory = new AgentMemory();
        memory.setMemoryType("FACT");
        memory.setContent(content);
        memory.setRelevanceScore(BigDecimal.ONE);
        return memory;
    }

    private static ChatResponse response(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }
}
