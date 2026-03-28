package com.schemaplexai.service.memory.rag;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.service.vector.EmbeddingService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档摄入管线（RAG Pipeline）
 *
 * <p>完整流程：Load → Clean → Split → Embed → Store
 * <ul>
 *   <li>Load: 使用 Apache Tika 解析 PDF/Word/Excel/TXT/Markdown/HTML</li>
 *   <li>Clean: 使用 {@link CleaningDocumentTransformer} 清洗文本</li>
 *   <li>Split: 使用 {@link DocumentSplitters#recursive} 递归分块</li>
 *   <li>Embed: 使用 {@link EmbeddingService} 生成向量</li>
 *   <li>Store: 写入 {@link EmbeddingStore} (Milvus)</li>
 * </ul>
 *
 * <p>处理状态跟踪到 sf_knowledge_document 表。
 */
@Slf4j
@Service
public class DocumentIngestionService {

    /** 分块大小（字符数） */
    private static final int CHUNK_SIZE = 1000;
    /** 分块重叠（字符数） */
    private static final int CHUNK_OVERLAP = 200;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingService embeddingService;
    private final KnowledgeDocumentMapper documentMapper;

    public DocumentIngestionService(
            @Autowired(required = false) EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingService embeddingService,
            KnowledgeDocumentMapper documentMapper) {
        this.embeddingStore = embeddingStore;
        this.embeddingService = embeddingService;
        this.documentMapper = documentMapper;
    }

    /**
     * 执行文档摄入管线
     *
     * @param documentId  知识文档记录 ID（sf_knowledge_document.id）
     * @param tenantId    租户 ID
     * @param contextId   关联的上下文 ID
     * @param fileStream  文件输入流
     * @param fileName    原始文件名
     */
    public void ingestDocument(String documentId, String tenantId, String contextId,
                               InputStream fileStream, String fileName) {
        if (embeddingStore == null) {
            log.warn("EmbeddingStore 不可用，跳过文档摄入: documentId={}", documentId);
            updateDocumentStatus(documentId, "failed", "EmbeddingStore 不可用", 0);
            return;
        }

        // 更新状态为处理中
        updateDocumentStatus(documentId, "processing", null, 0);

        try {
            // 1. 解析文档（Apache Tika 自动识别格式）
            ApacheTikaDocumentParser parser = new ApacheTikaDocumentParser();
            Document document = parser.parse(fileStream);
            log.info("文档解析完成: documentId={}, fileName={}, 原始长度={}",
                    documentId, fileName, document.text().length());

            // 2. 文本清洗
            CleaningDocumentTransformer cleaner = new CleaningDocumentTransformer();
            document = cleaner.transform(document);

            // 3. 文本分块
            DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
            List<TextSegment> segments = splitter.split(document);
            log.info("文档分块完成: documentId={}, chunkCount={}", documentId, segments.size());

            // 4. 向量化并存储
            int storedCount = 0;
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);

                // 注入元数据
                segment.metadata().put("tenant_id", tenantId);
                segment.metadata().put("context_id", contextId != null ? contextId : "");
                segment.metadata().put("document_id", documentId);
                segment.metadata().put("chunk_index", String.valueOf(i));
                segment.metadata().put("file_name", fileName);

                // 向量化
                float[] vector = embeddingService.embed(segment.text());
                Embedding embedding = Embedding.from(vector);

                // 存储到 EmbeddingStore
                embeddingStore.add(embedding, segment);
                storedCount++;
            }

            // 5. 更新文档状态
            updateDocumentStatus(documentId, "completed", null, storedCount);
            log.info("文档摄入完成: documentId={}, chunks={}", documentId, storedCount);

        } catch (Exception e) {
            log.error("文档摄入失败: documentId={}, error={}", documentId, e.getMessage(), e);
            updateDocumentStatus(documentId, "failed", e.getMessage(), 0);
        }
    }

    /**
     * 直接摄入纯文本内容（用于上下文条目的向量化）
     *
     * @param itemId     条目 ID
     * @param tenantId   租户 ID
     * @param contextId  上下文 ID
     * @param content    文本内容
     * @return 分块数量
     */
    public int ingestText(String itemId, String tenantId, String contextId, String content) {
        if (embeddingStore == null) {
            log.warn("EmbeddingStore 不可用，跳过文本摄入: itemId={}", itemId);
            return 0;
        }

        try {
            if (content == null || content.isBlank()) {
                return 0;
            }

            // 清洗
            Document document = Document.from(content);
            CleaningDocumentTransformer cleaner = new CleaningDocumentTransformer();
            document = cleaner.transform(document);

            // 分块
            DocumentSplitter splitter = DocumentSplitters.recursive(CHUNK_SIZE, CHUNK_OVERLAP);
            List<TextSegment> segments = splitter.split(document);

            // 向量化并存储
            for (int i = 0; i < segments.size(); i++) {
                TextSegment segment = segments.get(i);
                segment.metadata().put("tenant_id", tenantId);
                segment.metadata().put("context_id", contextId != null ? contextId : "");
                segment.metadata().put("item_id", itemId);
                segment.metadata().put("chunk_index", String.valueOf(i));

                float[] vector = embeddingService.embed(segment.text());
                embeddingStore.add(Embedding.from(vector), segment);
            }

            log.debug("文本摄入完成: itemId={}, chunks={}", itemId, segments.size());
            return segments.size();

        } catch (Exception e) {
            log.warn("文本摄入失败: itemId={}, error={}", itemId, e.getMessage());
            return 0;
        }
    }

    private void updateDocumentStatus(String documentId, String status, String errorMessage, int chunkCount) {
        try {
            LambdaUpdateWrapper<KnowledgeDocument> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(KnowledgeDocument::getId, documentId)
                    .set(KnowledgeDocument::getStatus, status)
                    .set(KnowledgeDocument::getChunkCount, chunkCount)
                    .set(KnowledgeDocument::getUpdatedAt, LocalDateTime.now());
            if (errorMessage != null) {
                wrapper.set(KnowledgeDocument::getErrorMessage, errorMessage);
            }
            documentMapper.update(null, wrapper);
        } catch (Exception e) {
            log.warn("更新文档状态失败: documentId={}, error={}", documentId, e.getMessage());
        }
    }
}
