package com.schemaplexai.service.rag.eval;

import com.schemaplexai.dao.mapper.RagConfigMapper;
import com.schemaplexai.model.entity.RagConfig;
import com.schemaplexai.model.vo.system.RagDiagnosticsVO;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.rag.retrieve.RetrievalCache;
import com.schemaplexai.service.rag.retrieve.RetrievalOutcome;
import com.schemaplexai.service.rag.retrieve.RetrievalTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 诊断：配置值、实测值、缓存与版本指纹是否如实反映。
 */
class RagDiagnosticsServiceTest {

    @Test
    @DisplayName("诊断输出应同时包含配置值与 BM25/缓存实测值")
    void shouldReportConfigAndRuntimeFacts() {
        RagConfigService configService = mock(RagConfigService.class);
        Bm25IndexService bm25 = mock(Bm25IndexService.class);
        RagConfigMapper configMapper = mock(RagConfigMapper.class);
        RetrievalCache cache = new RetrievalCache();
        cache.put("tenant-1|proj-A|q|v|6",
                RetrievalOutcome.empty(RetrievalTrace.builder().build()), 300);

        when(configService.resolveSettings(any())).thenReturn(RagRuntimeSettings.builder()
                .tenantId("tenant-1").enabled(true)
                .embeddingSource("api").collectionName("sf_context_items").embeddingDimension(1024)
                .vectorTopK(20).bm25Enabled(true).bm25TopK(20)
                .chunkSize(600).chunkOverlap(120)
                .sectionSplitEnabled(true).codeAstSplitEnabled(true)
                .tableBlockSplitEnabled(true).chunkAnchorEnabled(true)
                .fusionTopN(20).rrfK(60).retrievalTopK(6)
                .rerankerEnabled(true).rerankPolicy("auto").rerankerTopN(6).rerankTimeoutMs(1500)
                .maxFilesPerContext(2).entityOverlapFilterEnabled(true).contextTokenBudget(4000)
                .retrievalCacheEnabled(true).retrievalCacheTtlSeconds(300)
                .ocrMode("multimodal").ocrMaxConcurrency(2).maxFileSizeMb(50)
                .citationRequired(true).hitlOnLowFaithfulness(true).faithfulnessThreshold(0.7)
                .evalRecallK(20)
                .build());
        when(bm25.isAvailable()).thenReturn(true);
        when(bm25.chunkCount("tenant-1")).thenReturn(42L);
        RagConfig config = new RagConfig();
        config.setUpdatedAt(LocalDateTime.of(2026, 11, 25, 12, 0));
        when(configMapper.selectById("tenant-1")).thenReturn(config);

        RagDiagnosticsVO vo = new RagDiagnosticsService(configService, bm25, cache, configMapper)
                .diagnose("tenant-1");

        assertThat(vo.getEnabled()).isTrue();
        assertThat(vo.getBm25Available()).isTrue();
        assertThat(vo.getBm25ChunkCount()).isEqualTo(42L);
        assertThat(vo.getRerankActive()).isTrue();
        assertThat(vo.getRerankPolicy()).isEqualTo("auto");
        assertThat(vo.getContextTokenBudget()).isEqualTo(4000);
        assertThat(vo.getRetrievalCacheEntries()).isEqualTo(1);
        assertThat(vo.getRetrievalVersion()).contains("sf_context_items");
        assertThat(vo.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("RAG 关闭时诊断如实反映，不伪造 available")
    void shouldReportDisabledState() {
        RagConfigService configService = mock(RagConfigService.class);
        Bm25IndexService bm25 = mock(Bm25IndexService.class);
        RagConfigMapper configMapper = mock(RagConfigMapper.class);
        when(configService.resolveSettings(any())).thenReturn(
                RagRuntimeSettings.builder().tenantId("tenant-1").enabled(false).build());
        when(bm25.isAvailable()).thenReturn(false);
        when(configMapper.selectById(any())).thenReturn(null);

        RagDiagnosticsVO vo = new RagDiagnosticsService(configService, bm25, new RetrievalCache(), configMapper)
                .diagnose("tenant-1");

        assertThat(vo.getEnabled()).isFalse();
        assertThat(vo.getBm25Available()).isFalse();
        assertThat(vo.getBm25ChunkCount()).isZero();
        assertThat(vo.getUpdatedAt()).isNull();
    }
}
