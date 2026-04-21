package com.schemaplexai.service.memory.rag;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.constant.DocumentIngestionConstant;
import com.schemaplexai.common.enums.KnowledgeDocumentStatusEnum;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.model.entity.RagOperationLog;
import com.schemaplexai.service.rag.EmbeddingQuotaGuard;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.vector.EmbeddingService;
import com.schemaplexai.service.vector.EmbeddingTokenEstimator;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档摄入管线（RAG Pipeline）
 *
 * <p>完整流程：Load → Clean → Split → Embed → Store
 */
@Slf4j
@Service
public class DocumentIngestionService {

    private static final CleaningDocumentTransformer CLEANING_TRANSFORMER = new CleaningDocumentTransformer();

    private final RagMilvusStoreFactory ragMilvusStoreFactory;
    private final RagConfigService ragConfigService;
    private final EmbeddingQuotaGuard embeddingQuotaGuard;
    private final EmbeddingService embeddingService;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentStorageService documentStorageService;

    public DocumentIngestionService(
            @Autowired(required = false) RagMilvusStoreFactory ragMilvusStoreFactory,
            RagConfigService ragConfigService,
            EmbeddingQuotaGuard embeddingQuotaGuard,
            EmbeddingService embeddingService,
            KnowledgeDocumentMapper documentMapper,
            DocumentStorageService documentStorageService) {
        this.ragMilvusStoreFactory = ragMilvusStoreFactory;
        this.ragConfigService = ragConfigService;
        this.embeddingQuotaGuard = embeddingQuotaGuard;
        this.embeddingService = embeddingService;
        this.documentMapper = documentMapper;
        this.documentStorageService = documentStorageService;
    }

    @lombok.Getter
    public static class TextIngestionResult {

        private final String status;
        private final int chunkCount;
        private final String errorMessage;

        private TextIngestionResult(String status, int chunkCount, String errorMessage) {
            this.status = status;
            this.chunkCount = chunkCount;
            this.errorMessage = errorMessage;
        }

        public static TextIngestionResult success(int chunkCount) {
            return new TextIngestionResult(DocumentIngestionConstant.RESULT_SUCCESS, chunkCount, null);
        }

        public static TextIngestionResult failed(String errorMessage) {
            return new TextIngestionResult(DocumentIngestionConstant.RESULT_FAILED, 0, errorMessage);
        }

        public static TextIngestionResult skipped(String errorMessage) {
            return new TextIngestionResult(DocumentIngestionConstant.RESULT_SKIPPED, 0, errorMessage);
        }

        public boolean isSuccess() {
            return DocumentIngestionConstant.RESULT_SUCCESS.equalsIgnoreCase(status);
        }
    }

    /**
     * 执行文档摄入管线。
     *
     * <p>从 MinIO 加载文件 → 解析 → 清洗 → 分块 → 向量化 → 写入 Milvus。
     *
     * @param documentId 文档 ID（从 DB 查询 bucket/objectKey/tenantId/contextId）
     */
    public void ingestDocument(String documentId) {
        KnowledgeDocument doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.warn("文档不存在，跳过摄入: documentId={}", documentId);
            return;
        }

        String tenantId = doc.getTenantId();
        String contextId = doc.getContextId();
        String fileName = doc.getFileName();

        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        EmbeddingStore<TextSegment> embeddingStore = resolveStore(tenantId);
        if (!settings.isEnabled() || embeddingStore == null) {
            String errorMessage = !settings.isEnabled() ? "RAG 未启用" : "EmbeddingStore 不可用";
            log.warn("跳过文档摄入: documentId={}, tenantId={}, reason={}", documentId, tenantId, errorMessage);
            updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.FAILED.getCode(),
                    errorMessage, 0, 0, settings.getModelId());
            recordWriteOperation(DocumentIngestionConstant.SOURCE_KNOWLEDGE_DOCUMENT, documentId,
                    contextId, tenantId, settings, 0, 0, 0, 0L,
                    DocumentIngestionConstant.RESULT_FAILED, errorMessage, buildMetadata(fileName, settings));
            return;
        }

        updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.PROCESSING.getCode(),
                null, 0, 0, settings.getModelId());
        long startMs = System.currentTimeMillis();

        try (InputStream fileStream = documentStorageService.getObject(doc.getBucket(), doc.getObjectKey())) {
            ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
            Document document = parser.parse(fileStream);
            log.info("文档解析完成: documentId={}, fileName={}, 原始长度={}",
                    documentId, fileName, document.text().length());

            document = cleanDocument(document, settings.isTextCleaningEnabled());

            DocumentSplitter splitter = DocumentSplitters.recursive(settings.getChunkSize(), settings.getChunkOverlap());
            List<TextSegment> segments = splitter.split(document);
            log.info("文档分块完成: documentId={}, chunkCount={}", documentId, segments.size());
            int totalTokens = segments.stream()
                    .map(TextSegment::text)
                    .mapToInt(EmbeddingTokenEstimator::estimate)
                    .sum();
            embeddingQuotaGuard.ensureWithinQuota(tenantId, settings, totalTokens);

            removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_DOCUMENT_ID, documentId);

            List<String> ids = new ArrayList<>(segments.size());
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            List<TextSegment> embeddedSegments = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                segment.metadata().put(DocumentIngestionConstant.META_TENANT_ID, tenantId);
                segment.metadata().put(DocumentIngestionConstant.META_CONTEXT_ID, contextId != null ? contextId : "");
                segment.metadata().put(DocumentIngestionConstant.META_DOCUMENT_ID, documentId);
                segment.metadata().put(DocumentIngestionConstant.META_CHUNK_INDEX, String.valueOf(i));
                segment.metadata().put(DocumentIngestionConstant.META_FILE_NAME, fileName);
                ids.add(buildChunkId(documentId, i));
                embeddings.add(Embedding.from(embeddingService.embed(tenantId, segment.text())));
                embeddedSegments.add(segment);
            }
            if (!ids.isEmpty()) {
                embeddingStore.addAll(ids, embeddings, embeddedSegments);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.COMPLETED.getCode(),
                    null, ids.size(), totalTokens, settings.getModelId());
            recordWriteOperation(DocumentIngestionConstant.SOURCE_KNOWLEDGE_DOCUMENT, documentId,
                    contextId, tenantId, settings, ids.size(), document.text().length(), totalTokens,
                    durationMs, DocumentIngestionConstant.RESULT_SUCCESS, null, buildMetadata(fileName, settings));
            log.info("文档摄入完成: documentId={}, chunks={}, durationMs={}", documentId, ids.size(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("文档摄入失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.FAILED.getCode(),
                    e.getMessage(), 0, 0, settings.getModelId());
            recordWriteOperation(DocumentIngestionConstant.SOURCE_KNOWLEDGE_DOCUMENT, documentId,
                    contextId, tenantId, settings, 0, 0, 0, durationMs,
                    DocumentIngestionConstant.RESULT_FAILED, e.getMessage(), buildMetadata(fileName, settings));
        }
    }

    /**
     * 直接摄入纯文本内容（用于上下文条目的向量化）
     */
    public TextIngestionResult ingestText(String itemId, String tenantId, String contextId, String content) {
        RagRuntimeSettings settings = ragConfigService.resolveSettings(tenantId);
        EmbeddingStore<TextSegment> embeddingStore = resolveStore(tenantId);
        if (!settings.isEnabled() || embeddingStore == null) {
            String errorMessage = !settings.isEnabled() ? "RAG 未启用" : "EmbeddingStore 不可用";
            recordWriteOperation(DocumentIngestionConstant.SOURCE_CONTEXT_ITEM, itemId, contextId,
                    tenantId, settings, 0, content != null ? content.length() : 0, 0, 0L,
                    DocumentIngestionConstant.RESULT_SKIPPED, errorMessage, buildMetadata(null, settings));
            log.warn("EmbeddingStore 不可用或 RAG 未启用，跳过文本摄入: itemId={}", itemId);
            return TextIngestionResult.skipped(errorMessage);
        }

        long startMs = System.currentTimeMillis();
        try {
            if (!StringUtils.hasText(content)) {
                return TextIngestionResult.skipped("内容为空");
            }

            Document document = cleanDocument(Document.from(content), settings.isTextCleaningEnabled());
            DocumentSplitter splitter = DocumentSplitters.recursive(settings.getChunkSize(), settings.getChunkOverlap());
            List<TextSegment> segments = splitter.split(document);
            int totalTokens = segments.stream()
                    .map(TextSegment::text)
                    .mapToInt(EmbeddingTokenEstimator::estimate)
                    .sum();
            embeddingQuotaGuard.ensureWithinQuota(tenantId, settings, totalTokens);

            removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_ITEM_ID, itemId);

            List<String> ids = new ArrayList<>(segments.size());
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            List<TextSegment> embeddedSegments = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                segment.metadata().put(DocumentIngestionConstant.META_TENANT_ID, tenantId);
                segment.metadata().put(DocumentIngestionConstant.META_CONTEXT_ID, contextId != null ? contextId : "");
                segment.metadata().put(DocumentIngestionConstant.META_ITEM_ID, itemId);
                segment.metadata().put(DocumentIngestionConstant.META_CHUNK_INDEX, String.valueOf(i));
                ids.add(buildChunkId(itemId, i));
                embeddings.add(Embedding.from(embeddingService.embed(tenantId, segment.text())));
                embeddedSegments.add(segment);
            }
            if (!ids.isEmpty()) {
                embeddingStore.addAll(ids, embeddings, embeddedSegments);
            }

            long durationMs = System.currentTimeMillis() - startMs;
            recordWriteOperation(DocumentIngestionConstant.SOURCE_CONTEXT_ITEM, itemId, contextId,
                    tenantId, settings, ids.size(), content.length(), totalTokens, durationMs,
                    DocumentIngestionConstant.RESULT_SUCCESS, null, buildMetadata(null, settings));
            log.debug("文本摄入完成: itemId={}, chunks={}, durationMs={}", itemId, ids.size(), durationMs);
            return TextIngestionResult.success(ids.size());
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            recordWriteOperation(DocumentIngestionConstant.SOURCE_CONTEXT_ITEM, itemId, contextId,
                    tenantId, settings, 0, content != null ? content.length() : 0,
                    EmbeddingTokenEstimator.estimate(content), durationMs,
                    DocumentIngestionConstant.RESULT_FAILED, e.getMessage(), buildMetadata(null, settings));
            log.warn("文本摄入失败: itemId={}, error={}", itemId, e.getMessage());
            return TextIngestionResult.failed(e.getMessage());
        }
    }

    public void removeByItemId(String tenantId, String itemId) {
        EmbeddingStore<TextSegment> embeddingStore = resolveStore(tenantId);
        if (embeddingStore == null || !StringUtils.hasText(itemId)) {
            return;
        }
        removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_ITEM_ID, itemId);
    }

    public void removeByContextId(String tenantId, String contextId) {
        EmbeddingStore<TextSegment> embeddingStore = resolveStore(tenantId);
        if (embeddingStore == null || !StringUtils.hasText(contextId)) {
            return;
        }
        removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_CONTEXT_ID, contextId);
    }

    private EmbeddingStore<TextSegment> resolveStore(String tenantId) {
        return ragMilvusStoreFactory != null ? ragMilvusStoreFactory.getStore(tenantId) : null;
    }

    private Document cleanDocument(Document document, boolean cleaningEnabled) {
        if (!cleaningEnabled) {
            return document;
        }
        return CLEANING_TRANSFORMER.transform(document);
    }

    private void removeExistingSegments(EmbeddingStore<TextSegment> embeddingStore, String metadataKey, String value) {
        if (embeddingStore == null || !StringUtils.hasText(metadataKey) || !StringUtils.hasText(value)) {
            return;
        }
        try {
            Filter filter = MetadataFilterBuilder.metadataKey(metadataKey).isEqualTo(value);
            embeddingStore.removeAll(filter);
        } catch (Exception e) {
            log.debug("清理历史向量片段失败: metadataKey={}, value={}, error={}", metadataKey, value, e.getMessage());
        }
    }

    private void updateDocumentStatus(String documentId, String status, String errorMessage,
                                      int chunkCount, int totalTokens, String embeddingModel) {
        try {
            LambdaUpdateWrapper<KnowledgeDocument> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(KnowledgeDocument::getId, documentId)
                    .set(KnowledgeDocument::getStatus, status)
                    .set(KnowledgeDocument::getChunkCount, chunkCount)
                    .set(KnowledgeDocument::getTotalTokens, totalTokens)
                    .set(KnowledgeDocument::getEmbeddingModel, embeddingModel)
                    .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now());
            if (errorMessage != null) {
                wrapper.set(KnowledgeDocument::getErrorMessage, errorMessage);
            }
            documentMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("更新文档状态失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    private void recordWriteOperation(String sourceType, String sourceId, String contextId,
                                      String tenantId, RagRuntimeSettings settings, int chunkCount,
                                      int requestChars, int requestTokens, long durationMs, String status,
                                      String errorMessage, Map<String, Object> metadata) {
        RagOperationLog operationLog = new RagOperationLog();
        operationLog.setTenantId(tenantId);
        operationLog.setOperationType(DocumentIngestionConstant.OPERATION_WRITE);
        operationLog.setSourceType(sourceType);
        operationLog.setSourceId(sourceId);
        operationLog.setContextId(contextId);
        operationLog.setModelConfigId(settings.getVectorModelConfigId());
        operationLog.setModelName(settings.getVectorModelName());
        operationLog.setProvider(settings.getProvider());
        operationLog.setCollectionName(settings.getCollectionName());
        operationLog.setStatus(status);
        operationLog.setChunkCount(chunkCount);
        operationLog.setVectorDimension(settings.getEmbeddingDimension());
        operationLog.setRequestChars(requestChars);
        operationLog.setRequestTokens(requestTokens);
        operationLog.setDurationMs(durationMs);
        operationLog.setErrorMessage(errorMessage);
        operationLog.setMetadata(metadata);
        ragConfigService.recordOperation(operationLog);
    }

    private Map<String, Object> buildMetadata(String fileName, RagRuntimeSettings settings) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (StringUtils.hasText(fileName)) {
            metadata.put("fileName", fileName);
        }
        metadata.put("chunkSize", settings.getChunkSize());
        metadata.put("chunkOverlap", settings.getChunkOverlap());
        metadata.put("textCleaningEnabled", settings.isTextCleaningEnabled());
        return metadata;
    }

    private String buildChunkId(String sourceId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((sourceId + "#" + chunkIndex).getBytes()).toString();
    }
}
