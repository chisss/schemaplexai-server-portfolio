package com.schemaplexai.service.memory.rag;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4J MilvusEmbeddingStore 配置
 *
 * <p>替代直接使用 MilvusClientV2 原始 SDK，提供标准化的 EmbeddingStore 接口。
 * <p>激活条件与 {@link com.schemaplexai.service.vector.MilvusConfig} 一致。
 * <p>如果集合缺少索引导致初始化失败，会静默降级（不注册 Bean）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "milvus", name = "legacy-store-enabled", havingValue = "true")
public class MilvusEmbeddingStoreConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    /** 集合名称，与现有 MilvusVectorService 保持一致 */
    private static final String COLLECTION_NAME = "sf_context_items";

    /** 向量维度，与 OpenAI text-embedding-3-small 一致 */
    private static final int DIMENSION = 1536;

    public MilvusEmbeddingStore milvusEmbeddingStore() {
        String uri = String.format("http://%s:%d", host, port);
        log.info("初始化 LangChain4J MilvusEmbeddingStore: uri={}, collection={}", uri, COLLECTION_NAME);

        try {
            MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
                    .uri(uri)
                    .collectionName(COLLECTION_NAME)
                    .dimension(DIMENSION)
                    .consistencyLevel(ConsistencyLevelEnum.STRONG)
                    .autoFlushOnInsert(false)
                    .retrieveEmbeddingsOnSearch(false)
                    .build();
            log.info("MilvusEmbeddingStore 初始化成功");
            return store;
        } catch (Exception e) {
            log.error("MilvusEmbeddingStore 初始化失败（集合可能缺少索引），RAG 功能将不可用: {}", e.getMessage());
            return null;
        }
    }
}
