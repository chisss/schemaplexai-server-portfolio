package com.schemaplexai.service.memory.rag;

import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCandidate;
import com.schemaplexai.service.rag.retrieve.RetrievalOrchestrator;
import com.schemaplexai.service.rag.retrieve.RetrievalOutcome;
import com.schemaplexai.service.rag.retrieve.RetrievalTrace;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ContentRetriever 适配层：开关判定与协议转换。
 */
class RagContentRetrieverFactoryTest {

    @Test
    @DisplayName("编排器不可用时返回 null，Agent 侧按无 RAG 处理")
    @SuppressWarnings("unchecked")
    void shouldReturnNullWhenOrchestratorMissing() {
        ObjectProvider<RetrievalOrchestrator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RagConfigService configService = mock(RagConfigService.class);
        RagContentRetrieverFactory factory = new RagContentRetrieverFactory(configService, provider);

        assertThat(factory.createRetriever("tenant-1", 5, 0.5)).isNull();
    }

    @Test
    @DisplayName("RAG 关闭时返回 null")
    @SuppressWarnings("unchecked")
    void shouldReturnNullWhenRagDisabled() {
        ObjectProvider<RetrievalOrchestrator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(RetrievalOrchestrator.class));
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(any())).thenReturn(
                RagRuntimeSettings.builder().tenantId("tenant-1").enabled(false).build());
        RagContentRetrieverFactory factory = new RagContentRetrieverFactory(configService, provider);

        assertThat(factory.createRetriever("tenant-1")).isNull();
    }

    @Test
    @DisplayName("启用时把编排结果映射为带来源元数据的 Content")
    @SuppressWarnings("unchecked")
    void shouldMapOutcomeToContents() {
        RetrievalOrchestrator orchestrator = mock(RetrievalOrchestrator.class);
        RetrievalCandidate candidate = new RetrievalCandidate("c1", "doc-1", "额度说明.pdf", "1.1 额度",
                "document", "【来源：额度说明.pdf › 1.1 额度】最高额度五万元",
                null, 1, null, 0.9, 0.03, 0.77, "bm25+vector");
        when(orchestrator.retrieve(any())).thenReturn(new RetrievalOutcome(
                List.of(candidate), "[1] 内容", RetrievalTrace.builder().build()));

        ObjectProvider<RetrievalOrchestrator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(orchestrator);
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(any())).thenReturn(enabledSettings());
        RagContentRetrieverFactory factory = new RagContentRetrieverFactory(configService, provider);

        ContentRetriever retriever = factory.createRetriever("tenant-1", List.of("proj-A"));
        List<Content> contents = retriever.retrieve(Query.from("现金贷额度"));

        assertThat(contents).hasSize(1);
        assertThat(contents.get(0).textSegment().text()).contains("最高额度五万元");
        assertThat(contents.get(0).textSegment().metadata().getString("fileName")).isEqualTo("额度说明.pdf");
        assertThat(contents.get(0).textSegment().metadata().getString("chunkId")).isEqualTo("c1");
    }

    @Test
    @DisplayName("编排器无命中时返回空列表，不抛异常")
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyWhenNoHits() {
        RetrievalOrchestrator orchestrator = mock(RetrievalOrchestrator.class);
        when(orchestrator.retrieve(any())).thenReturn(
                RetrievalOutcome.empty(RetrievalTrace.builder().degradedReason("NO_HITS").build()));
        ObjectProvider<RetrievalOrchestrator> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(orchestrator);
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(any())).thenReturn(enabledSettings());
        RagContentRetrieverFactory factory = new RagContentRetrieverFactory(configService, provider);

        ContentRetriever retriever = factory.createRetriever("tenant-1");

        assertThat(retriever.retrieve(Query.from("不存在的主题"))).isEmpty();
    }

    private RagRuntimeSettings enabledSettings() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .collectionName("sf_context_items")
                .retrievalTopK(6)
                .rrfK(RagDefaults.RRF_K)
                .build();
    }
}
