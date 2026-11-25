package com.schemaplexai.service.agent.execution;

import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.agent.memory.AgentMemoryExtractionService;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.memory.rag.RagContentRetrieverFactory;
import com.schemaplexai.service.user.UserMemoryInjectionService;
import com.schemaplexai.service.user.UserMemoryPromptPart;
import com.schemaplexai.service.vector.MilvusVectorService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ContextInjectorTest {

    @Test
    void shouldIncludeBoundProjectContextInStaticPromptWithoutRag() throws Exception {
        AgentContextBindingMapper agentContextBindingMapper = mock(AgentContextBindingMapper.class);
        ContextEntityMapper contextEntityMapper = mock(ContextEntityMapper.class);
        ContextItemMapper contextItemMapper = mock(ContextItemMapper.class);
        ContextCacheService contextCacheService = mock(ContextCacheService.class);

        when(contextCacheService.getAgentPrompt("agent-1")).thenReturn(null);

        AgentContextBinding binding = new AgentContextBinding();
        binding.setAgentId("agent-1");
        binding.setContextId("ctx-project");
        binding.setTitle("titanium-policy 工作区研发上下文");
        binding.setStatus("active");
        when(agentContextBindingMapper.selectList(any())).thenReturn(List.of(binding));

        ContextItem instructionItem = new ContextItem();
        instructionItem.setContent("你是需求分析师，请先读真实仓库再输出。");
        when(contextItemMapper.selectOne(any())).thenReturn(instructionItem);

        ContextEntity projectContext = new ContextEntity();
        projectContext.setId("ctx-project");
        projectContext.setName("项目研发上下文");
        projectContext.setContextLevel("project");
        projectContext.setStatus("active");
        when(contextEntityMapper.selectById("ctx-project")).thenReturn(projectContext);
        when(contextEntityMapper.selectList(any())).thenReturn(List.of());

        ContextItem ignoredInstruction = new ContextItem();
        ignoredInstruction.setIsAgentInstructions(true);
        ignoredInstruction.setContent("这段指令不应重复进入绑定上下文");
        ContextItem projectInfo = new ContextItem();
        projectInfo.setIsAgentInstructions(false);
        projectInfo.setContent("真实模块包括 titanium-policy-api、titanium-policy-domain、titanium-policy-web。");
        when(contextItemMapper.selectList(any())).thenReturn(List.of(ignoredInstruction, projectInfo));

        ContextInjector injector = new ContextInjector(
                agentContextBindingMapper,
                contextEntityMapper,
                contextItemMapper,
                contextCacheService
        );

        String prompt = injector.buildSystemPrompt("agent-1", "请输出需求分析");

        assertThat(prompt).contains("你是需求分析师，请先读真实仓库再输出。");
        assertThat(prompt).contains("## 绑定上下文");
        assertThat(prompt).contains("### titanium-policy 工作区研发上下文");
        assertThat(prompt).contains("真实模块包括 titanium-policy-api、titanium-policy-domain、titanium-policy-web。");
        assertThat(prompt).doesNotContain("这段指令不应重复进入绑定上下文");
        verify(contextCacheService).cacheAgentPrompt(eq("agent-1"), anyString());
    }

    @Test
    void shouldIncludeSemanticSectionFromRagRetriever() throws Exception {
        AgentContextBindingMapper agentContextBindingMapper = mock(AgentContextBindingMapper.class);
        ContextCacheService contextCacheService = mock(ContextCacheService.class);
        RagContentRetrieverFactory ragContentRetrieverFactory = mock(RagContentRetrieverFactory.class);
        MilvusVectorService milvusVectorService = mock(MilvusVectorService.class);
        when(contextCacheService.getAgentPrompt("agent-1")).thenReturn("## 静态指令");
        AgentContextBinding binding = new AgentContextBinding();
        binding.setContextId("ctx-1");
        when(agentContextBindingMapper.selectList(any())).thenReturn(List.of(binding));
        ContentRetriever retriever = query -> List.of(Content.from(TextSegment.from("RAG 命中片段")));
        when(ragContentRetrieverFactory.createRetriever(anyString(), anyCollection())).thenReturn(retriever);

        ContextInjector injector = new ContextInjector(
                agentContextBindingMapper,
                mock(ContextEntityMapper.class),
                mock(ContextItemMapper.class),
                contextCacheService
        );
        setField(injector, "ragContentRetrieverFactory", ragContentRetrieverFactory);
        setField(injector, "milvusVectorService", milvusVectorService);

        var result = injector.buildSystemPromptDetail("agent-1", "请输出回归方案", "tenant-1", null,
                List.of("本轮执行规则"), null);
        String prompt = result.prompt();

        assertThat(prompt).contains("## 静态指令");
        assertThat(prompt).contains("## 相关背景知识（语义检索）");
        assertThat(prompt).contains("RAG 命中片段");
        assertThat(prompt).contains("## 当前任务上下文");
        assertThat(prompt).contains("请输出回归方案");
        assertThat(result.taskContext()).isNotNull();
        assertThat(prompt.substring(result.taskContext().start(), result.taskContext().end()))
                .isEqualTo("\n\n---\n\n## 当前任务上下文\n请输出回归方案");
        assertThat(result.taskContext().removeFrom(prompt))
                .contains("## 静态指令", "本轮执行规则", "RAG 命中片段")
                .doesNotContain("## 当前任务上下文");
        assertThat(result.dynamicChars()).isEqualTo(
                "## 相关背景知识（语义检索）\nRAG 命中片段\n\n---\n\n## 当前任务上下文\n请输出回归方案".length());
        verify(milvusVectorService, never()).searchSimilarContext(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    void shouldKeepCompressedTaskSectionInCanonicalPrompt() {
        var cache = mock(ContextCacheService.class);
        when(cache.getAgentPrompt("agent-1")).thenReturn("## 静态指令");
        var injector = new ContextInjector(mock(AgentContextBindingMapper.class), mock(ContextEntityMapper.class),
                mock(ContextItemMapper.class), cache);
        String longTask = "长任务上下文".repeat(5000);

        var result = injector.buildSystemPromptDetail("agent-1", longTask, null, null, List.of(), null);

        assertThat(result.taskContext()).isNull();
        assertThat(result.prompt()).contains("## 当前任务上下文", "上下文已压缩").doesNotContain(longTask);
    }

    @Test
    void shouldFallbackToMilvusWhenRagRetrieverUnavailable() throws Exception {
        AgentContextBindingMapper agentContextBindingMapper = mock(AgentContextBindingMapper.class);
        ContextCacheService contextCacheService = mock(ContextCacheService.class);
        RagContentRetrieverFactory ragContentRetrieverFactory = mock(RagContentRetrieverFactory.class);
        MilvusVectorService milvusVectorService = mock(MilvusVectorService.class);
        when(contextCacheService.getAgentPrompt("agent-1")).thenReturn("## 静态指令");
        AgentContextBinding binding = new AgentContextBinding();
        binding.setContextId("ctx-1");
        binding.setStatus("active");
        when(agentContextBindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(ragContentRetrieverFactory.createRetriever(anyString(), anyCollection()))
                .thenThrow(new IllegalStateException("RAG retriever unavailable"));
        when(milvusVectorService.searchSimilarContext("tenant-1", List.of("ctx-1"), "请继续补齐上下文", 5))
                .thenReturn(List.of("Milvus 命中片段 A", "Milvus 命中片段 B"));

        ContextInjector injector = new ContextInjector(
                agentContextBindingMapper,
                mock(ContextEntityMapper.class),
                mock(ContextItemMapper.class),
                contextCacheService
        );
        setField(injector, "ragContentRetrieverFactory", ragContentRetrieverFactory);
        setField(injector, "milvusVectorService", milvusVectorService);

        String prompt = injector.buildSystemPrompt("agent-1", "请继续补齐上下文", "tenant-1", null);

        assertThat(prompt).contains("## 相关背景知识（语义检索）");
        assertThat(prompt).contains("Milvus 命中片段 A");
        assertThat(prompt).contains("Milvus 命中片段 B");
        verify(milvusVectorService).searchSimilarContext("tenant-1", List.of("ctx-1"), "请继续补齐上下文", 5);
    }

    @Test
    void shouldIncludeUserMemoryWhenUserIdProvided() throws Exception {
        AgentContextBindingMapper agentContextBindingMapper = mock(AgentContextBindingMapper.class);
        ContextCacheService contextCacheService = mock(ContextCacheService.class);
        UserMemoryInjectionService userMemoryInjectionService = mock(UserMemoryInjectionService.class);
        when(contextCacheService.getAgentPrompt("agent-1")).thenReturn("## 静态指令");
        when(agentContextBindingMapper.selectList(any())).thenReturn(List.of());
        when(userMemoryInjectionService.buildPromptPart(
                "tenant-1", "user-1", "agent-1", null, "请继续", false))
                .thenReturn(new UserMemoryPromptPart(
                        "## 用户画像与偏好\n- [PREFERENCE|USER_AGENT|EXPLICIT] 用户偏好中文输出",
                        "",
                        1,
                        0));

        ContextInjector injector = new ContextInjector(
                agentContextBindingMapper,
                mock(ContextEntityMapper.class),
                mock(ContextItemMapper.class),
                contextCacheService
        );
        setField(injector, "userMemoryInjectionService", userMemoryInjectionService);

        ContextInjector.PromptBuildResult result = injector.buildSystemPromptDetail(
                "agent-1", "请继续", "tenant-1", null, null, null,
                "user-1", null, false);

        assertThat(result.prompt()).contains("## 用户画像与偏好");
        assertThat(result.prompt()).contains("用户偏好中文输出");
        assertThat(result.userMemoryStaticCount()).isEqualTo(1);
    }

    @Test
    void shouldSkipLongTermMemoryAndGeneratedInstructionsInTemporaryMode() throws Exception {
        AgentContextBindingMapper bindingMapper = mock(AgentContextBindingMapper.class);
        ContextItemMapper itemMapper = mock(ContextItemMapper.class);
        ContextCacheService cache = mock(ContextCacheService.class);
        UserMemoryInjectionService userMemory = mock(UserMemoryInjectionService.class);
        AgentMemoryExtractionService agentMemory = mock(AgentMemoryExtractionService.class);
        AgentContextBinding automatic = new AgentContextBinding();
        automatic.setContextId("auto");
        AgentContextBinding manual = new AgentContextBinding();
        manual.setContextId("manual");
        ContextItem legacyAuto = new ContextItem();
        legacyAuto.setTitle("Agent 专属指令（自动生成）");
        legacyAuto.setContent("旧 Agent 记忆");
        ContextItem newlyMarked = new ContextItem();
        newlyMarked.setTitle("其他专属指令");
        newlyMarked.setContent("新 Agent 记忆");
        newlyMarked.setMetadata(Map.of("derivedFromAgentMemory", true));
        ContextItem authored = new ContextItem();
        authored.setContent("人工设置的执行指令");
        when(bindingMapper.selectList(any())).thenReturn(List.of(automatic, manual, manual));
        when(itemMapper.selectOne(any())).thenReturn(legacyAuto, newlyMarked, authored);
        ContextInjector injector = new ContextInjector(bindingMapper, mock(ContextEntityMapper.class), itemMapper, cache);
        setField(injector, "userMemoryInjectionService", userMemory);
        setField(injector, "agentMemoryService", agentMemory);

        ContextInjector.PromptBuildResult result = injector.buildSystemPromptDetail(
                "agent", "当前运行任务", "tenant", null, null, null, "user", null, true);

        assertThat(result.prompt()).contains("人工设置的执行指令", "当前运行任务")
                .doesNotContain("旧 Agent 记忆", "新 Agent 记忆");
        verifyNoInteractions(userMemory, agentMemory, cache);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ContextInjector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
