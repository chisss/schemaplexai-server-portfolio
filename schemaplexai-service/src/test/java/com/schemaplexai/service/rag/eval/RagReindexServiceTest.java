package com.schemaplexai.service.rag.eval;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.common.enums.KnowledgeDocumentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重建索引：dry-run、批量提交、入队失败回滚、RAG 关闭拦截。
 */
class RagReindexServiceTest {

    private KnowledgeDocumentMapper documentMapper;
    private DocumentIngestionService ingestionService;
    private RagConfigService ragConfigService;
    private RetrievalCache cache;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeDocument.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(KnowledgeDocumentMapper.class);
        ingestionService = mock(DocumentIngestionService.class);
        ragConfigService = mock(RagConfigService.class);
        cache = new RetrievalCache();
        when(ragConfigService.resolveSettings(anyString())).thenReturn(
                RagRuntimeSettings.builder().tenantId("tenant-1").enabled(true).build());
    }

    @Test
    @DisplayName("dry-run 只统计，不改状态也不入队")
    void shouldNotTouchDataOnDryRun() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc("d1"), doc("d2")));
        RagReindexService service = new RagReindexService(documentMapper, ingestionService,
                ragConfigService, cache, Runnable::run);

        RagReindexService.ReindexReport report = service.reindex("tenant-1", "ctx-1", true, 100);

        assertThat(report.scanned()).isEqualTo(2);
        assertThat(report.queued()).isZero();
        assertThat(report.dryRun()).isTrue();
        verify(documentMapper, never()).update(any(), any(Wrapper.class));
        verify(ingestionService, never()).ingestDocument(anyString());
    }

    @Test
    @DisplayName("正常批次：每条文档置 pending 并提交摄入")
    void shouldQueueAllDocuments() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc("d1"), doc("d2"), doc("d3")));
        AtomicInteger executed = new AtomicInteger();
        Executor executor = command -> {
            executed.incrementAndGet();
            command.run();
        };
        RagReindexService service = new RagReindexService(documentMapper, ingestionService,
                ragConfigService, cache, executor);

        RagReindexService.ReindexReport report = service.reindex("tenant-1", null, false, 50);

        assertThat(report.scanned()).isEqualTo(3);
        assertThat(report.queued()).isEqualTo(3);
        assertThat(report.rejected()).isZero();
        assertThat(executed.get()).isEqualTo(3);
        verify(ingestionService, times(3)).ingestDocument(anyString());
        // 每条文档一次置 pending
        verify(documentMapper, times(3)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("队列拒绝时计数为 rejected，且不调用摄入")
    void shouldCountRejectedWhenQueueFull() {
        when(documentMapper.selectList(any())).thenReturn(List.of(doc("d1")));
        Executor rejecting = command -> {
            throw new RejectedExecutionException("queue full");
        };
        RagReindexService service = new RagReindexService(documentMapper, ingestionService,
                ragConfigService, cache, rejecting);

        RagReindexService.ReindexReport report = service.reindex("tenant-1", null, false, 10);

        assertThat(report.queued()).isZero();
        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.notes()).contains("d1");
        verify(ingestionService, never()).ingestDocument(anyString());
        // 置 pending + 回滚各一次
        verify(documentMapper, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    @DisplayName("RAG 未启用时拒绝重建")
    void shouldRejectWhenRagDisabled() {
        when(ragConfigService.resolveSettings(anyString())).thenReturn(
                RagRuntimeSettings.builder().tenantId("tenant-1").enabled(false).build());
        RagReindexService service = new RagReindexService(documentMapper, ingestionService,
                ragConfigService, cache, Runnable::run);

        assertThatThrownBy(() -> service.reindex("tenant-1", null, false, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RAG 未启用");
    }

    @Test
    @DisplayName("空结果集返回全零，不抛异常")
    void shouldHandleEmptyResult() {
        when(documentMapper.selectList(any())).thenReturn(List.of());
        RagReindexService service = new RagReindexService(documentMapper, ingestionService,
                ragConfigService, cache, Runnable::run);

        RagReindexService.ReindexReport report = service.reindex("tenant-1", null, false, 10);

        assertThat(report.scanned()).isZero();
        assertThat(report.queued()).isZero();
    }

    private KnowledgeDocument doc(String id) {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(id);
        doc.setTenantId("tenant-1");
        doc.setContextId("ctx-1");
        doc.setStatus(KnowledgeDocumentStatusEnum.COMPLETED.getCode());
        doc.setDeleted(0);
        return doc;
    }
}
