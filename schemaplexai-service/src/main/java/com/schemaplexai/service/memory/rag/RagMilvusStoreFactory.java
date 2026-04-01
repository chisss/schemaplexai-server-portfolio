package com.schemaplexai.service.memory.rag;

import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按租户 RAG 配置创建 Milvus EmbeddingStore
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagMilvusStoreFactory {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    private final RagConfigService ragConfigService;
    private final ConcurrentMap<String, EmbeddingStore<TextSegment>> storeCache = new ConcurrentHashMap<>();

    /**
     * 基于租户配置获取 EmbeddingStore；未启用时返回 null
     */
    public EmbeddingStore<TextSegment> getStore(String tenantId) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (settings == null || !settings.isEnabled() || !StringUtils.hasText(settings.getCollectionName())) {
            return null;
        }
        String cacheKey = host + ":" + port + "|" + settings.getCollectionName() + "|" + settings.getEmbeddingDimension();
        return storeCache.computeIfAbsent(cacheKey, key -> buildStore(settings));
    }

    private EmbeddingStore<TextSegment> buildStore(RagRuntimeSettings settings) {
        String uri = String.format("http://%s:%d", host, port);
        log.info("初始化租户级 MilvusEmbeddingStore: tenantId={}, uri={}, collection={}, dimension={}",
                settings.getTenantId(), uri, settings.getCollectionName(), settings.getEmbeddingDimension());
        return MilvusEmbeddingStore.builder()
                .uri(uri)
                .collectionName(settings.getCollectionName())
                .dimension(settings.getEmbeddingDimension())
                .consistencyLevel(ConsistencyLevelEnum.STRONG)
                .autoFlushOnInsert(false)
                .retrieveEmbeddingsOnSearch(false)
                .build();
    }
}
