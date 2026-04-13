package com.schemaplexai.service.agent.execution;

import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.memory.rag.RagContentRetrieverFactory;
import com.schemaplexai.service.vector.MilvusVectorService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        String prompt = injector.buildSystemPrompt("agent-1", "请输出回归方案", "tenant-1", null);

        assertThat(prompt).contains("## 静态指令");
        assertThat(prompt).contains("## 相关背景知识（语义检索）");
        assertThat(prompt).contains("RAG 命中片段");
        assertThat(prompt).contains("## 当前任务上下文");
        assertThat(prompt).contains("请输出回归方案");
        verify(milvusVectorService, never()).searchSimilarContext(anyString(), anyString(), anyString(), anyInt());
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
        when(agentContextBindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(ragContentRetrieverFactory.createRetriever(anyString(), anyCollection()))
                .thenThrow(new IllegalStateException("RAG retriever unavailable"));
        when(milvusVectorService.searchSimilarContext("tenant-1", "agent-1", "请继续补齐上下文", 5))
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
        verify(milvusVectorService).searchSimilarContext("tenant-1", "agent-1", "请继续补齐上下文", 5);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ContextInjector.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
