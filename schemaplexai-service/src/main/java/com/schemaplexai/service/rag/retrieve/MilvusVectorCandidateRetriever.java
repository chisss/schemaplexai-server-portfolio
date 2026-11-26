package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.memory.rag.EmbeddingModelProvider;
import com.schemaplexai.service.memory.rag.RagMilvusStoreFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Milvus 向量召回（Bi-Encoder 双塔）。
 *
 * <p>关键点是<b>预过滤</b>：tenant_id / context_id 作为 filter 传给向量库，
 * 在 ANN 检索阶段就裁剪候选空间，而不是取回 TopK 再在内存里筛。
 * 后者在小数据量下看不出问题，数据一多就会"召回的全是别的租户，过滤完只剩两条"。
 */
@Slf4j
@Component
public class MilvusVectorCandidateRetriever implements VectorCandidateRetriever {

    /** 元数据键，与摄入侧写入保持一致 */
    public static final String META_TENANT_ID = "tenant_id";
    public static final String META_CONTEXT_ID = "context_id";
    public static final String META_DOCUMENT_ID = "document_id";
    public static final String META_CHUNK_ID = "chunk_id";
    public static final String META_FILE_NAME = "file_name";
    public static final String META_SECTION = "section";
    public static final String META_CATEGORY = "doc_category";

    private final ObjectProvider<RagMilvusStoreFactory> storeFactoryProvider;
    private final EmbeddingModelProvider embeddingModelProvider;

    public MilvusVectorCandidateRetriever(ObjectProvider<RagMilvusStoreFactory> storeFactoryProvider,
                                          EmbeddingModelProvider embeddingModelProvider) {
        this.storeFactoryProvider = storeFactoryProvider;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public boolean isAvailable(String tenantId) {
        RagMilvusStoreFactory factory = storeFactoryProvider.getIfAvailable();
        return factory != null && StringUtils.hasText(tenantId);
    }

    @Override
    public List<RrfFusion.VectorHit> retrieve(String tenantId, Collection<String> projectIds,
                                              String query, int topK) {
        RagMilvusStoreFactory factory = storeFactoryProvider.getIfAvailable();
        if (factory == null || !StringUtils.hasText(tenantId) || !StringUtils.hasText(query)) {
            return List.of();
        }
        EmbeddingStore<TextSegment> store = factory.getStore(tenantId);
        if (store == null) {
            return List.of();
        }
        try {
            Embedding queryEmbedding = embeddingModelProvider.getEmbeddingModel(tenantId)
                    .embed(query).content();

            Filter filter = MetadataFilterBuilder.metadataKey(META_TENANT_ID).isEqualTo(tenantId);
            if (!CollectionUtils.isEmpty(projectIds)) {
                filter = new dev.langchain4j.store.embedding.filter.logical.And(filter,
                        MetadataFilterBuilder.metadataKey(META_CONTEXT_ID).isIn(projectIds));
            }

            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(Math.max(1, topK))
                    .filter(filter)
                    .build());

            List<RrfFusion.VectorHit> hits = new ArrayList<>(result.matches().size());
            for (EmbeddingMatch<TextSegment> match : result.matches()) {
                TextSegment segment = match.embedded();
                if (segment == null) {
                    continue;
                }
                String chunkId = segment.metadata().getString(META_CHUNK_ID);
                String docId = segment.metadata().getString(META_DOCUMENT_ID);
                if (!StringUtils.hasText(chunkId)) {
                    chunkId = docId + "#" + segment.metadata().getString("chunk_index");
                }
                hits.add(new RrfFusion.VectorHit(
                        chunkId,
                        docId,
                        segment.metadata().getString(META_FILE_NAME),
                        segment.metadata().getString(META_SECTION),
                        segment.metadata().getString(META_CATEGORY),
                        segment.text(),
                        match.score()));
            }
            return hits;
        } catch (Exception e) {
            log.warn("向量召回失败，本次退化为 BM25 单路: tenantId={}, error={}", tenantId, e.getMessage());
            return List.of();
        }
    }
}
