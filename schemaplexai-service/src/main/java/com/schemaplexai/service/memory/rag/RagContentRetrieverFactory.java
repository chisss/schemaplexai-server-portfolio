package com.schemaplexai.service.memory.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import com.schemaplexai.model.entity.RagOperationLog;
import com.schemaplexai.service.rag.EmbeddingQuotaGuard;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.vector.EmbeddingTokenEstimator;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.filter.logical.And;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG ContentRetriever 工厂
 *
 * <p>为每次 Agent 执行构建带租户隔离的 ContentRetriever。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagContentRetrieverFactory {

    private final RagMilvusStoreFactory ragMilvusStoreFactory;
    private final EmbeddingModelProvider embeddingModelProvider;
    private final RagConfigService ragConfigService;
    private final EmbeddingQuotaGuard embeddingQuotaGuard;

    /**
     * 为指定租户构建 ContentRetriever
     *
     * @param tenantId 租户 ID（用于数据隔离过滤）
     * @return ContentRetriever 实例，如果 EmbeddingModel 不可用则返回 null
     */
    public ContentRetriever createRetriever(String tenantId) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        return createRetriever(tenantId, List.of(), settings.getRetrievalTopK(), settings.getRetrievalMinScore());
    }

    /**
     * 为指定租户与上下文范围构建 ContentRetriever。
     */
    public ContentRetriever createRetriever(String tenantId, Collection<String> contextIds) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        return createRetriever(tenantId, contextIds, settings.getRetrievalTopK(), settings.getRetrievalMinScore());
    }

    /**
     * 为指定租户构建 ContentRetriever（自定义参数）
     */
    public ContentRetriever createRetriever(String tenantId, int maxResults, double minScore) {
        return createRetriever(tenantId, List.of(), maxResults, minScore);
    }

    private ContentRetriever createRetriever(String tenantId, Collection<String> contextIds, int maxResults, double minScore) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        if (!settings.isEnabled()) {
            log.debug("RAG 未启用，跳过检索: tenantId={}", tenantId);
            return null;
        }
        EmbeddingStore<TextSegment> embeddingStore = ragMilvusStoreFactory != null
                ? ragMilvusStoreFactory.getStore(tenantId)
                : null;
        EmbeddingModel embeddingModel = embeddingModelProvider.getEmbeddingModel(tenantId);
        if (embeddingModel == null || embeddingStore == null) {
            log.debug("EmbeddingModel 不可用，跳过 RAG 检索");
            return null;
        }

        List<String> scopedContextIds = contextIds == null
                ? List.of()
                : contextIds.stream().filter(StringUtils::hasText).distinct().toList();

        return query -> {
            long startMs = System.currentTimeMillis();
            RagOperationLog operationLog = new RagOperationLog();
            operationLog.setTenantId(tenantId);
            operationLog.setOperationType("query");
            operationLog.setSourceType("agent_query");
            if (scopedContextIds.size() == 1) {
                operationLog.setContextId(scopedContextIds.get(0));
            }
            operationLog.setCollectionName(settings.getCollectionName());
            operationLog.setModelConfigId(settings.getVectorModelConfigId());
            operationLog.setModelName(settings.getVectorModelName());
            operationLog.setProvider(settings.getProvider());
            operationLog.setVectorDimension(settings.getEmbeddingDimension());
            operationLog.setRequestChars(query.text() != null ? query.text().length() : 0);
            operationLog.setRequestTokens(EmbeddingTokenEstimator.estimate(query.text()));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("maxResults", maxResults);
            metadata.put("minScore", minScore);
            if (!scopedContextIds.isEmpty()) {
                metadata.put("contextIds", scopedContextIds);
            }
            operationLog.setMetadata(metadata);

            try {
                embeddingQuotaGuard.ensureWithinQuota(tenantId, settings, operationLog.getRequestTokens());
                Filter filter = MetadataFilterBuilder.metadataKey("tenant_id").isEqualTo(tenantId);
                if (!CollectionUtils.isEmpty(scopedContextIds)) {
                    filter = new And(filter, MetadataFilterBuilder.metadataKey("context_id").isIn(scopedContextIds));
                }
                EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(embeddingModel.embed(query.text()).content())
                        .maxResults(maxResults)
                        .minScore(minScore)
                        .filter(filter)
                        .build());
                Set<String> matchedContextIds = new LinkedHashSet<>();
                searchResult.matches().stream()
                        .map(EmbeddingMatch::embedded)
                        .map(TextSegment::metadata)
                        .map(item -> item.getString("context_id"))
                        .filter(StringUtils::hasText)
                        .forEach(matchedContextIds::add);
                if (!matchedContextIds.isEmpty()) {
                    operationLog.getMetadata().put("matchedContextIds", matchedContextIds.stream().toList());
                    if (!StringUtils.hasText(operationLog.getContextId()) && matchedContextIds.size() == 1) {
                        operationLog.setContextId(matchedContextIds.iterator().next());
                    }
                }
                List<Content> contents = searchResult.matches().stream()
                        .map(EmbeddingMatch::embedded)
                        .map(Content::from)
                        .toList();
                operationLog.setStatus("success");
                operationLog.setRetrievedCount(contents.size());
                operationLog.setDurationMs(System.currentTimeMillis() - startMs);
                ragConfigService.recordOperation(operationLog);
                return contents;
            } catch (Exception e) {
                operationLog.setStatus("failed");
                operationLog.setRetrievedCount(0);
                operationLog.setDurationMs(System.currentTimeMillis() - startMs);
                operationLog.setErrorMessage(e.getMessage());
                ragConfigService.recordOperation(operationLog);
                log.warn("RAG 检索失败: tenantId={}, error={}", tenantId, e.getMessage());
                return List.of();
            }
        };
    }
}
