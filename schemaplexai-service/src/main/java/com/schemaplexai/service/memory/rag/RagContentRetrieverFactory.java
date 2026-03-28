package com.schemaplexai.service.memory.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * RAG ContentRetriever 工厂
 *
 * <p>为每次 Agent 执行构建带租户隔离的 ContentRetriever。
 * 使用 LangChain4J 标准的 {@link EmbeddingStoreContentRetriever}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContentRetrieverFactory {

    /** 默认最大检索结果数 */
    private static final int DEFAULT_MAX_RESULTS = 5;
    /** 默认最小相似度分数 */
    private static final double DEFAULT_MIN_SCORE = 0.6;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModelProvider embeddingModelProvider;

    /**
     * 为指定租户构建 ContentRetriever
     *
     * @param tenantId 租户 ID（用于数据隔离过滤）
     * @return ContentRetriever 实例，如果 EmbeddingModel 不可用则返回 null
     */
    public ContentRetriever createRetriever(String tenantId) {
        return createRetriever(tenantId, DEFAULT_MAX_RESULTS, DEFAULT_MIN_SCORE);
    }

    /**
     * 为指定租户构建 ContentRetriever（自定义参数）
     */
    public ContentRetriever createRetriever(String tenantId, int maxResults, double minScore) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getEmbeddingModel();
        if (embeddingModel == null) {
            log.debug("EmbeddingModel 不可用，跳过 RAG 检索");
            return null;
        }

        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(tenantId))
                .build();
    }
}
