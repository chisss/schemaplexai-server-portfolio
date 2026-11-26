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
import com.schemaplexai.service.rag.ingest.ComplianceScanner;
import com.schemaplexai.service.rag.ingest.FileGatewayService;
import com.schemaplexai.service.rag.ingest.GatewayDecision;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.parse.DocumentParseException;
import com.schemaplexai.service.rag.ingest.parse.DocumentParseRouter;
import com.schemaplexai.service.rag.ingest.parse.ParseContext;
import com.schemaplexai.service.rag.ingest.parse.ParsedDocument;
import com.schemaplexai.service.rag.ingest.split.RoutedChunk;
import com.schemaplexai.service.rag.ingest.split.RouterDocumentSplitter;
import com.schemaplexai.service.rag.index.Bm25Chunk;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.rag.retrieve.MilvusVectorCandidateRetriever;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.vector.EmbeddingService;
import com.schemaplexai.service.vector.EmbeddingTokenEstimator;
import dev.langchain4j.data.document.Document;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档摄入管线（RAG v2）。
 *
 * <pre>
 * 网关判定 → 路由解析 → 路由切分 → chunk 级二次合规 → 双索引写入 → 版本+1 → 缓存失效
 * </pre>
 *
 * <p>相对 v1 的三个关键变化：
 * <ol>
 *   <li>不再"一律 Tika + recursive 切分"，按分类走不同解析器与切分器；</li>
 *   <li>每个 chunk 同时写 BM25 与向量，chunkId 双索引共用，融合时才能对得上；</li>
 *   <li>写入前先按 file_id 删旧 chunk，并让检索缓存按项目失效 —— 否则文件更新后仍会命中旧答案。</li>
 * </ol>
 *
 * <p>纯文本摄入（上下文条目）保留 v1 的窗口切分：条目本身就是短文本，不需要章节/代码策略。
 */
@Slf4j
@Service
public class DocumentIngestionService {

    private final RagMilvusStoreFactory ragMilvusStoreFactory;
    private final RagConfigService ragConfigService;
    private final EmbeddingQuotaGuard embeddingQuotaGuard;
    private final EmbeddingService embeddingService;
    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentStorageService documentStorageService;
    private final FileGatewayService fileGatewayService;
    private final DocumentParseRouter documentParseRouter;
    private final RouterDocumentSplitter routerDocumentSplitter;
    private final ComplianceScanner complianceScanner;
    private final Bm25IndexService bm25IndexService;
    private final RetrievalCache retrievalCache;

    public DocumentIngestionService(
            @Autowired(required = false) RagMilvusStoreFactory ragMilvusStoreFactory,
            RagConfigService ragConfigService,
            EmbeddingQuotaGuard embeddingQuotaGuard,
            EmbeddingService embeddingService,
            KnowledgeDocumentMapper documentMapper,
            DocumentStorageService documentStorageService,
            FileGatewayService fileGatewayService,
            DocumentParseRouter documentParseRouter,
            RouterDocumentSplitter routerDocumentSplitter,
            ComplianceScanner complianceScanner,
            Bm25IndexService bm25IndexService,
            RetrievalCache retrievalCache) {
        this.ragMilvusStoreFactory = ragMilvusStoreFactory;
        this.ragConfigService = ragConfigService;
        this.embeddingQuotaGuard = embeddingQuotaGuard;
        this.embeddingService = embeddingService;
        this.documentMapper = documentMapper;
        this.documentStorageService = documentStorageService;
        this.fileGatewayService = fileGatewayService;
        this.documentParseRouter = documentParseRouter;
        this.routerDocumentSplitter = routerDocumentSplitter;
        this.complianceScanner = complianceScanner;
        this.bm25IndexService = bm25IndexService;
        this.retrievalCache = retrievalCache;
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
            fail(documentId, contextId, tenantId, fileName, settings, errorMessage, 0);
            return;
        }

        updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.PROCESSING.getCode(),
                null, 0, 0, settings.getModelId(), null, null, null);
        long startMs = System.currentTimeMillis();

        try (InputStream fileStream = documentStorageService.getObject(doc.getBucket(), doc.getObjectKey())) {
            // 上传侧已按 50MB 拦截，但租户可把上限调大，这里在读取前再守一道，避免大文件把堆打满
            long declaredSize = doc.getFileSize() == null ? 0L : doc.getFileSize();
            long hardLimit = Math.max(1, settings.getMaxFileSizeMb()) * 1024L * 1024L;
            if (declaredSize > hardLimit && declaredSize > 0) {
                fail(documentId, contextId, tenantId, fileName, settings,
                        "[TOO_LARGE] 文件 " + (declaredSize / 1024 / 1024) + "MB 超过租户上限 "
                                + settings.getMaxFileSizeMb() + "MB", 0);
                return;
            }
            byte[] content = fileStream.readAllBytes();

            // ── 1. 网关判定（类型/容量/黑名单/前置合规） ──
            GatewayDecision decision = fileGatewayService.inspect(fileName, content,
                    textPreviewOf(content, doc.getMimeType()), settings);
            if (!decision.accepted()) {
                fail(documentId, contextId, tenantId, fileName, settings,
                        "[" + decision.rejectCode() + "] " + decision.rejectReason(), content.length);
                return;
            }

            // ── 2. 路由解析 ──
            ParseContext parseContext = new ParseContext(fileName, decision.detectedMime(),
                    decision.category(), content, settings);
            ParsedDocument parsed = documentParseRouter.parse(parseContext);
            if (!parsed.hasText()) {
                String reason = parsed.scannedFallback()
                        ? "解析结果为空：疑似扫描件且 OCR 未产出有效文本"
                        : "解析结果为空，文件可能没有可提取文本";
                fail(documentId, contextId, tenantId, fileName, settings, reason, content.length);
                return;
            }

            // ── 3. 路由切分 ──
            List<RoutedChunk> chunks = routerDocumentSplitter.split(parsed, fileName, settings);
            if (chunks.isEmpty()) {
                fail(documentId, contextId, tenantId, fileName, settings, "切分结果为空", content.length);
                return;
            }

            // ── 4. chunk 级二次合规 ──
            List<RoutedChunk> safeChunks = new ArrayList<>(chunks.size());
            int blockedChunks = 0;
            for (RoutedChunk chunk : chunks) {
                if (complianceScanner.isBlocking(chunk.body())) {
                    blockedChunks++;
                    continue;
                }
                safeChunks.add(chunk);
            }
            if (safeChunks.isEmpty()) {
                fail(documentId, contextId, tenantId, fileName, settings,
                        "全部 " + blockedChunks + " 个片段命中合规规则，已丢弃", content.length);
                return;
            }

            int totalTokens = safeChunks.stream().mapToInt(c -> EmbeddingTokenEstimator.estimate(c.text())).sum();
            embeddingQuotaGuard.ensureWithinQuota(tenantId, settings, totalTokens);

            // ── 5. 清理旧版本（双索引同步） ──
            removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_DOCUMENT_ID, documentId);
            bm25IndexService.deleteByDocument(tenantId, documentId);

            // ── 6. 双索引写入 ──
            List<String> ids = new ArrayList<>(safeChunks.size());
            List<Embedding> embeddings = new ArrayList<>(safeChunks.size());
            List<TextSegment> segments = new ArrayList<>(safeChunks.size());
            List<Bm25Chunk> bm25Chunks = new ArrayList<>(safeChunks.size());
            String projectId = contextId != null ? contextId : "";

            for (RoutedChunk chunk : safeChunks) {
                String chunkId = buildChunkId(documentId, chunk.index());
                TextSegment segment = TextSegment.from(chunk.text(), buildMetadata(
                        tenantId, projectId, documentId, chunkId, chunk, fileName, decision.category()));
                ids.add(chunkId);
                embeddings.add(Embedding.from(embeddingService.embed(tenantId, chunk.text())));
                segments.add(segment);
                bm25Chunks.add(new Bm25Chunk(chunkId, tenantId, projectId, documentId, fileName,
                        chunk.section(), decision.category().code(), chunk.text(), chunk.bm25Text()));
            }
            embeddingStore.addAll(ids, embeddings, segments);
            bm25IndexService.upsertChunks(bm25Chunks);

            // ── 7. 版本与缓存 ──
            int indexVersion = (doc.getIndexVersion() == null ? 0 : doc.getIndexVersion()) + 1;
            long durationMs = System.currentTimeMillis() - startMs;
            updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.COMPLETED.getCode(),
                    null, safeChunks.size(), totalTokens, settings.getModelId(),
                    decision.category().code(), parsed.parserId(), indexVersion);
            retrievalCache.invalidateProject(tenantId, projectId);

            Map<String, Object> metadata = buildMetadata(fileName, settings);
            metadata.put("docCategory", decision.category().code());
            metadata.put("sourceParser", parsed.parserId());
            metadata.put("blockedChunks", blockedChunks);
            metadata.put("warnings", concatWarnings(decision.warnings(), parsed.warnings()));
            recordWriteOperation(DocumentIngestionConstant.SOURCE_KNOWLEDGE_DOCUMENT, documentId,
                    contextId, tenantId, settings, safeChunks.size(), parsed.text().length(), totalTokens,
                    durationMs, DocumentIngestionConstant.RESULT_SUCCESS, null, metadata);

            log.info("文档摄入完成: documentId={}, category={}, parser={}, chunks={}, blocked={}, durationMs={}",
                    documentId, decision.category().code(), parsed.parserId(), safeChunks.size(),
                    blockedChunks, durationMs);
        } catch (DocumentParseException e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.warn("文档解析失败: documentId={}, code={}, error={}", documentId, e.getCode(), e.getMessage());
            fail(documentId, contextId, tenantId, fileName, settings,
                    "[" + e.getCode() + "] " + e.getMessage(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            log.error("文档摄入失败: documentId={}, error={}", documentId, e.getMessage(), e);
            fail(documentId, contextId, tenantId, fileName, settings,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), durationMs);
        }
    }

    /**
     * 直接摄入纯文本内容（上下文条目向量化）。
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
        if (!StringUtils.hasText(content)) {
            return TextIngestionResult.skipped("内容为空");
        }
        if (complianceScanner.isBlocking(content)) {
            return TextIngestionResult.failed("内容命中合规规则，已拒绝入库");
        }

        long startMs = System.currentTimeMillis();
        try {
            Document document = new CleaningDocumentTransformer().transform(Document.from(content));
            List<TextSegment> segments = DocumentSplitters
                    .recursive(settings.getChunkSize(), settings.getChunkOverlap())
                    .split(document);
            int totalTokens = segments.stream().map(TextSegment::text)
                    .mapToInt(EmbeddingTokenEstimator::estimate).sum();
            embeddingQuotaGuard.ensureWithinQuota(tenantId, settings, totalTokens);

            removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_ITEM_ID, itemId);
            bm25IndexService.deleteByDocument(tenantId, itemId);

            List<String> ids = new ArrayList<>(segments.size());
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            List<Bm25Chunk> bm25Chunks = new ArrayList<>(segments.size());
            String projectId = contextId != null ? contextId : "";

            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                String chunkId = buildChunkId(itemId, i);
                segment.metadata().put(DocumentIngestionConstant.META_TENANT_ID, tenantId);
                segment.metadata().put(DocumentIngestionConstant.META_CONTEXT_ID, projectId);
                segment.metadata().put("project_id", projectId);
                segment.metadata().put(DocumentIngestionConstant.META_ITEM_ID, itemId);
                segment.metadata().put(MilvusVectorCandidateRetriever.META_CHUNK_ID, chunkId);
                segment.metadata().put(DocumentIngestionConstant.META_CHUNK_INDEX, String.valueOf(i));
                ids.add(chunkId);
                embeddings.add(Embedding.from(embeddingService.embed(tenantId, segment.text())));
                bm25Chunks.add(new Bm25Chunk(chunkId, tenantId, projectId, itemId, null,
                        "上下文条目", IngestCategory.DOCUMENT.code(), segment.text(), segment.text()));
            }
            if (!ids.isEmpty()) {
                embeddingStore.addAll(ids, embeddings, segments);
                bm25IndexService.upsertChunks(bm25Chunks);
            }
            retrievalCache.invalidateProject(tenantId, projectId);

            long durationMs = System.currentTimeMillis() - startMs;
            recordWriteOperation(DocumentIngestionConstant.SOURCE_CONTEXT_ITEM, itemId, contextId,
                    tenantId, settings, ids.size(), content.length(), totalTokens, durationMs,
                    DocumentIngestionConstant.RESULT_SUCCESS, null, buildMetadata(null, settings));
            return TextIngestionResult.success(ids.size());
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startMs;
            recordWriteOperation(DocumentIngestionConstant.SOURCE_CONTEXT_ITEM, itemId, contextId,
                    tenantId, settings, 0, content.length(), EmbeddingTokenEstimator.estimate(content),
                    durationMs, DocumentIngestionConstant.RESULT_FAILED, e.getMessage(),
                    buildMetadata(null, settings));
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
        bm25IndexService.deleteByDocument(tenantId, itemId);
    }

    public void removeByContextId(String tenantId, String contextId) {
        EmbeddingStore<TextSegment> embeddingStore = resolveStore(tenantId);
        if (embeddingStore == null || !StringUtils.hasText(contextId)) {
            return;
        }
        removeExistingSegments(embeddingStore, DocumentIngestionConstant.META_CONTEXT_ID, contextId);
        bm25IndexService.deleteByDocument(tenantId, contextId);
        retrievalCache.invalidateProject(tenantId, contextId);
    }

    // ==================== 内部 ====================

    private dev.langchain4j.data.document.Metadata buildMetadata(String tenantId, String projectId,
                                                                 String documentId, String chunkId,
                                                                 RoutedChunk chunk, String fileName,
                                                                 IngestCategory category) {
        dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata();
        metadata.put(DocumentIngestionConstant.META_TENANT_ID, tenantId);
        metadata.put(DocumentIngestionConstant.META_CONTEXT_ID, projectId);
        metadata.put("project_id", projectId);
        metadata.put(DocumentIngestionConstant.META_DOCUMENT_ID, documentId);
        metadata.put(MilvusVectorCandidateRetriever.META_CHUNK_ID, chunkId);
        metadata.put(DocumentIngestionConstant.META_CHUNK_INDEX, String.valueOf(chunk.index()));
        metadata.put(DocumentIngestionConstant.META_FILE_NAME, fileName == null ? "" : fileName);
        metadata.put(MilvusVectorCandidateRetriever.META_SECTION, chunk.section() == null ? "" : chunk.section());
        metadata.put(MilvusVectorCandidateRetriever.META_CATEGORY, category.code());
        return metadata;
    }

    /** 仅文本类文件做网关期合规预览，二进制交给 chunk 级扫描 */
    private String textPreviewOf(byte[] content, String mimeType) {
        if (mimeType == null || !mimeType.startsWith("text/")) {
            return null;
        }
        int limit = Math.min(content.length, 32 * 1024);
        return new String(content, 0, limit, StandardCharsets.UTF_8);
    }

    private EmbeddingStore<TextSegment> resolveStore(String tenantId) {
        return ragMilvusStoreFactory != null ? ragMilvusStoreFactory.getStore(tenantId) : null;
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

    private void fail(String documentId, String contextId, String tenantId, String fileName,
                      RagRuntimeSettings settings, String errorMessage, long durationMs) {
        updateDocumentStatus(documentId, KnowledgeDocumentStatusEnum.FAILED.getCode(),
                errorMessage, 0, 0, settings.getModelId(), null, null, null);
        recordWriteOperation(DocumentIngestionConstant.SOURCE_KNOWLEDGE_DOCUMENT, documentId,
                contextId, tenantId, settings, 0, 0, 0, durationMs,
                DocumentIngestionConstant.RESULT_FAILED, errorMessage, buildMetadata(fileName, settings));
    }

    private void updateDocumentStatus(String documentId, String status, String errorMessage,
                                      int chunkCount, int totalTokens, String embeddingModel,
                                      String docCategory, String sourceParser, Integer indexVersion) {
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
            if (docCategory != null) {
                wrapper.set(KnowledgeDocument::getDocCategory, docCategory);
            }
            if (sourceParser != null) {
                wrapper.set(KnowledgeDocument::getSourceParser, sourceParser);
            }
            if (indexVersion != null) {
                wrapper.set(KnowledgeDocument::getIndexVersion, indexVersion);
            }
            documentMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("更新文档状态失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }

    private List<String> concatWarnings(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
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
        metadata.put("pipeline", "v2");
        return metadata;
    }

    private String buildChunkId(String sourceId, int chunkIndex) {
        return UUID.nameUUIDFromBytes((sourceId + "#" + chunkIndex).getBytes()).toString();
    }
}
