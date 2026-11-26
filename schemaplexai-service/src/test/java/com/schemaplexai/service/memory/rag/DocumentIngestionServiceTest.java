package com.schemaplexai.service.memory.rag;

import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.service.rag.EmbeddingQuotaGuard;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.ComplianceScanner;
import com.schemaplexai.service.rag.ingest.FileGatewayService;
import com.schemaplexai.service.rag.ingest.parse.DocumentParseRouter;
import com.schemaplexai.service.rag.ingest.parse.ExcelMarkdownParseHandler;
import com.schemaplexai.service.rag.ingest.parse.MultimodalOcrParseHandler;
import com.schemaplexai.service.rag.ingest.parse.TikaTextParseHandler;
import com.schemaplexai.service.rag.ingest.split.CodeAstSplitter;
import com.schemaplexai.service.rag.ingest.split.RouterDocumentSplitter;
import com.schemaplexai.service.rag.ingest.split.SectionSemanticSplitter;
import com.schemaplexai.service.rag.ingest.split.TableBlockSplitter;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import com.schemaplexai.service.storage.DocumentStorageService;
import com.schemaplexai.service.vector.EmbeddingService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 摄入管线 v2：网关拦截、解析路由、双索写入、版本与缓存。
 */
class DocumentIngestionServiceTest {

    @TempDir
    Path tempDir;

    private KnowledgeDocumentMapper documentMapper;
    private DocumentStorageService storageService;
    private RagConfigService ragConfigService;
    private RagMilvusStoreFactory storeFactory;
    private EmbeddingStore<TextSegment> embeddingStore;
    private EmbeddingService embeddingService;
    private Bm25IndexService bm25IndexService;
    private RetrievalCache retrievalCache;
    private DocumentIngestionService service;

    /** LambdaUpdateWrapper 依赖 MyBatis-Plus 的 TableInfo 缓存，纯单测需要手工初始化 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        storageService = mock(DocumentStorageService.class);
        ragConfigService = mock(RagConfigService.class);
        storeFactory = mock(RagMilvusStoreFactory.class);
        embeddingStore = mock(EmbeddingStore.class);
        embeddingService = mock(EmbeddingService.class);
        bm25IndexService = new Bm25IndexService(tempDir.resolve("bm25").toString());
        bm25IndexService.init();
        retrievalCache = new RetrievalCache();

        when(storeFactory.getStore(anyString())).thenReturn(embeddingStore);
        when(embeddingService.embed(anyString(), anyString())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        ComplianceScanner scanner = new ComplianceScanner();
        service = new DocumentIngestionService(
                storeFactory,
                ragConfigService,
                mock(EmbeddingQuotaGuard.class),
                embeddingService,
                documentMapper,
                storageService,
                new FileGatewayService(scanner),
                new DocumentParseRouter(List.of(new TikaTextParseHandler(), new ExcelMarkdownParseHandler(),
                        new MultimodalOcrParseHandler(List.of()))),
                new RouterDocumentSplitter(new SectionSemanticSplitter(), new CodeAstSplitter(),
                        new TableBlockSplitter()),
                scanner,
                bm25IndexService,
                retrievalCache);
    }

    @AfterEach
    void tearDown() {
        bm25IndexService.close();
    }

    @Test
    @DisplayName("普通文档：网关通过 → 章节切分 → 双索引写入 → 状态 COMPLETED")
    void shouldIngestDocumentThroughV2Pipeline() {
        stubDocument("doc-1", "产品说明.txt", "text/plain", """
                # 产品说明
                现金贷产品最高额度五万元，期限最长三十六期。
                ## 利率
                年化利率三点六起，按日计息。
                """, 0);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings());

        service.ingestDocument("doc-1");

        verify(embeddingStore, atLeastOnce()).addAll(anyList(), anyList(), anyList());
        verify(documentMapper, atLeastOnce()).update(isNull(), any());
        assertThat(bm25IndexService.chunkCount("tenant-1")).isGreaterThan(0);
    }

    @Test
    @DisplayName("重复摄入：先删旧 chunk 再写新，chunk 总数不翻倍")
    void shouldReplaceOldChunksOnReingest() {
        stubDocument("doc-1", "产品说明.txt", "text/plain", "现金贷额度五万元，期限三十六期。", 0);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings());
        service.ingestDocument("doc-1");
        long firstCount = bm25IndexService.chunkCount("tenant-1");

        stubDocument("doc-1", "产品说明.txt", "text/plain", "现金贷额度十万元，期限二十四期。", 1);
        service.ingestDocument("doc-1");

        assertThat(bm25IndexService.chunkCount("tenant-1")).isEqualTo(firstCount);
    }

    @Test
    @DisplayName("超过租户容量上限：网关拦截，不写任何索引")
    void shouldRejectOversizedFile() {
        stubDocument("doc-big", "big.pdf", "application/pdf", "%PDF-1.7 tiny", 0);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(
                settingsBuilder().maxFileSizeMb(1).build());
        // 伪造大文件：storage 返回 2MB 内容
        byte[] big = new byte[2 * 1024 * 1024];
        big[0] = '%';
        big[1] = 'P';
        big[2] = 'D';
        big[3] = 'F';
        when(storageService.getObject(any(), any())).thenReturn(new ByteArrayInputStream(big));

        service.ingestDocument("doc-big");

        verify(embeddingStore, never()).addAll(anyList(), anyList(), anyList());
        assertThat(bm25IndexService.chunkCount("tenant-1")).isZero();
    }

    @Test
    @DisplayName("批量隐私数据：前置合规拦截，文件标记失败")
    void shouldRejectBulkPrivacyContent() {
        String text = "客户清单：110101199001011234，110101199001011235，110101199001011236";
        stubDocument("doc-pii", "客户清单.txt", "text/plain", text, 0);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings());

        service.ingestDocument("doc-pii");

        verify(embeddingStore, never()).addAll(anyList(), anyList(), anyList());
        assertThat(bm25IndexService.chunkCount("tenant-1")).isZero();
    }

    @Test
    @DisplayName("可执行文件伪装成文档：网关拒绝")
    void shouldRejectDisguisedExecutable() {
        byte[] mz = new byte[128];
        mz[0] = 'M';
        mz[1] = 'Z';
        stubDocument("doc-exe", "合同.pdf", "application/pdf", null, 0);
        when(storageService.getObject(any(), any())).thenReturn(new ByteArrayInputStream(mz));
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings());

        service.ingestDocument("doc-exe");

        verify(embeddingStore, never()).addAll(anyList(), anyList(), anyList());
    }

    @Test
    @DisplayName("RAG 未启用：直接标记失败，不读存储")
    void shouldFailFastWhenRagDisabled() {
        stubDocument("doc-1", "a.txt", "text/plain", "内容", 0);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settingsBuilder().enabled(false).build());

        service.ingestDocument("doc-1");

        verify(storageService, never()).getObject(any(), any());
    }

    @Test
    @DisplayName("上下文条目文本摄入：写双索引并使项目缓存失效")
    void shouldIngestPlainTextItem() {
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings());
        retrievalCache.put("tenant-1|proj-1|q|v|6", emptyOutcome(), 300);

        DocumentIngestionService.TextIngestionResult result = service.ingestText(
                "item-1", "tenant-1", "proj-1", "额度审批口径：单人最高五万元。");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getChunkCount()).isGreaterThan(0);
        verify(embeddingStore, atLeastOnce()).addAll(anyList(), anyList(), anyList());
        assertThat(retrievalCache.size()).isZero();
    }

    // ==================== 辅助 ====================

    private void stubDocument(String id, String fileName, String mime, String text, int indexVersion) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setTenantId("tenant-1");
        doc.setContextId("proj-1");
        doc.setFileName(fileName);
        doc.setMimeType(mime);
        doc.setBucket("kb");
        doc.setObjectKey("tenant-1/" + id + "/" + fileName);
        doc.setIndexVersion(indexVersion);
        when(documentMapper.selectById(id)).thenReturn(doc);

        byte[] content = text == null ? new byte[]{1, 2, 3} : text.getBytes(StandardCharsets.UTF_8);
        when(storageService.getObject(any(), any())).thenReturn(new ByteArrayInputStream(content));
    }

    private com.schemaplexai.service.rag.retrieve.RetrievalOutcome emptyOutcome() {
        return com.schemaplexai.service.rag.retrieve.RetrievalOutcome.empty(
                com.schemaplexai.service.rag.retrieve.RetrievalTrace.builder().build());
    }

    private RagRuntimeSettings settings() {
        return settingsBuilder().build();
    }

    private RagRuntimeSettings.RagRuntimeSettingsBuilder settingsBuilder() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .collectionName("sf_context_items")
                .chunkSize(600)
                .chunkOverlap(100)
                .retrievalTopK(6)
                .embeddingDimension(3)
                .maxFileSizeMb(50)
                .ingestCodeEnabled(true)
                .ingestExcelEnabled(true)
                .ocrMode(RagDefaults.OCR_MODE_DISABLED)
                .ocrMinTextChars(20)
                .sectionSplitEnabled(true)
                .codeAstSplitEnabled(true)
                .tableBlockSplitEnabled(true)
                .chunkAnchorEnabled(true)
                .bm25Enabled(true)
                .bm25TopK(20)
                .vectorTopK(20)
                .maxFilesPerContext(2)
                .contextTokenBudget(4000);
    }
}
