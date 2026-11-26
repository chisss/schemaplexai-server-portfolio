package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.index.Bm25Chunk;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.vector.ScoringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索编排器：并行召回、降级、缓存、Token 预算、精排超时回落。
 */
class RetrievalOrchestratorTest {

    @TempDir
    Path tempDir;

    private Bm25IndexService bm25IndexService;
    private RagConfigService ragConfigService;
    private StubVectorRetriever vectorRetriever;
    private RetrievalCache cache;
    private final Executor directExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        bm25IndexService = new Bm25IndexService(tempDir.resolve("bm25").toString());
        bm25IndexService.init();
        ragConfigService = mock(RagConfigService.class);
        vectorRetriever = new StubVectorRetriever();
        cache = new RetrievalCache();
    }

    @AfterEach
    void tearDown() {
        bm25IndexService.close();
    }

    @Test
    @DisplayName("双路命中 → RRF 融合 → 引用块带编号与来源")
    void shouldFuseBothPathsAndAssembleContext() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元，期限最长三十六期");
        indexChunk("c2", "doc-1", "额度说明.pdf", "年化利率三点六起，按日计息");
        vectorRetriever.hits = List.of(
                new RrfFusion.VectorHit("c1", "doc-1", "额度说明.pdf", "1.1 额度", "document",
                        "【来源：额度说明.pdf › 1.1 额度 › #0】\n现金贷产品最高额度为五万元，期限最长三十六期", 0.91),
                new RrfFusion.VectorHit("c3", "doc-2", "利率说明.pdf", "1.2 利率", "document",
                        "【来源：利率说明.pdf › 1.2 利率 › #0】\n年化利率区间为三点六到十八", 0.77));
        RetrievalOrchestrator orchestrator = orchestrator(settings(), null);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度"));

        assertThat(outcome.isEmpty()).isFalse();
        assertThat(outcome.contextBlock()).contains("[1] 【来源：");
        assertThat(outcome.trace().bm25Used()).isTrue();
        assertThat(outcome.trace().vectorUsed()).isTrue();
        assertThat(outcome.trace().fusedCount()).isGreaterThanOrEqualTo(2);
        // c1 两路都命中，应排第一
        assertThat(outcome.chunks().get(0).chunkId()).isEqualTo("c1");
        assertThat(outcome.chunks().get(0).matchedBy()).isEqualTo("bm25+vector");
    }

    @Test
    @DisplayName("向量不可用时降级为 BM25 单路，不影响返回")
    void shouldDegradeToBm25Only() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元");
        vectorRetriever.available = false;
        RetrievalOrchestrator orchestrator = orchestrator(settings(), null);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度"));

        assertThat(outcome.isEmpty()).isFalse();
        assertThat(outcome.trace().bm25Used()).isTrue();
        assertThat(outcome.trace().vectorUsed()).isFalse();
    }

    @Test
    @DisplayName("BM25 索引不可用时降级为向量单路")
    void shouldDegradeToVectorOnly() {
        Bm25IndexService brokenIndex = new Bm25IndexService(tempDir.resolve("broken").toString());
        // 不调用 init() → writer 为 null → isAvailable=false
        vectorRetriever.hits = List.of(new RrfFusion.VectorHit("c9", "doc-9", "a.pdf", "正文", "document",
                "【来源：a.pdf › 正文 › #0】现金贷额度五万元", 0.8));
        RetrievalOrchestrator orchestrator = orchestratorWithIndex(settings(), null, brokenIndex);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度"));

        assertThat(outcome.trace().bm25Used()).isFalse();
        assertThat(outcome.trace().vectorUsed()).isTrue();
        assertThat(outcome.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("第二次相同查询命中缓存，trace.cacheHit=true")
    void shouldHitCacheOnSecondCall() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元");
        RetrievalOrchestrator orchestrator = orchestrator(settings(), null);
        RetrievalQuery query = RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度");

        orchestrator.retrieve(query);
        RetrievalOutcome second = orchestrator.retrieve(query);

        assertThat(second.trace().cacheHit()).isTrue();
        assertThat(second.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("关闭缓存时不命中，每次都重新检索")
    void shouldNotCacheWhenDisabled() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元");
        RetrievalOrchestrator orchestrator = orchestrator(
                builder().retrievalCacheEnabled(false).build(), null);
        RetrievalQuery query = RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度");

        orchestrator.retrieve(query);
        RetrievalOutcome second = orchestrator.retrieve(query);

        assertThat(second.trace().cacheHit()).isFalse();
    }

    @Test
    @DisplayName("Token 预算很小时只保留少量片段")
    void shouldRespectTokenBudget() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷额度五万元");
        indexChunk("c2", "doc-2", "利率说明.pdf", "现金贷利率三点六");
        indexChunk("c3", "doc-3", "罚息说明.pdf", "现金贷罚息一点五倍");
        RetrievalOrchestrator orchestrator = orchestrator(builder().contextTokenBudget(5).build(), null);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度利率罚息"));

        assertThat(outcome.chunks()).hasSize(1);
        assertThat(outcome.trace().droppedByTokenBudget()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("RAG 未启用时返回空并标明原因")
    void shouldReturnEmptyWhenRagDisabled() {
        RetrievalOrchestrator orchestrator = orchestrator(builder().enabled(false).build(), null);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "任意查询"));

        assertThat(outcome.isEmpty()).isTrue();
        assertThat(outcome.trace().degradedReason()).isEqualTo("RAG_DISABLED");
    }

    @Test
    @DisplayName("精排超时自动回落 RRF 原序，不让检索挂掉")
    void shouldFallbackWhenRerankTimesOut() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷产品最高额度为五万元");
        ScoringService slowScorer = new ScoringService() {
            @Override
            public List<ScoredPassage> rerank(String query, List<String> passages, int topN) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
        RetrievalOrchestrator orchestrator = new RetrievalOrchestrator(
                ragConfigService, bm25IndexService, vectorRetriever,
                scorerProvider(slowScorer),
                new RrfFusion(),
                new RerankGate(new RrfFusion()),
                new ContextFilterChain(new EntityMatcher()),
                new ContextBlockAssembler(),
                cache,
                java.util.concurrent.Executors.newFixedThreadPool(2),
                300, 300);
        when(ragConfigService.resolveSettings(anyString())).thenReturn(
                builder().rerankerEnabled(true).rerankPolicy("always").rerankTimeoutMs(100).build());

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度"));

        assertThat(outcome.isEmpty()).isFalse();
        assertThat(outcome.trace().rerankDegraded()).isTrue();
        assertThat(outcome.trace().degradedReason()).contains("RERANK_TIMEOUT");
    }

    @Test
    @DisplayName("短关键词查询走 BM25 快通道，跳过向量召回")
    void shouldUseKeywordFastPath() {
        indexChunk("c1", "doc-1", "额度说明.pdf", "现金贷额度五万元");
        RetrievalOrchestrator orchestrator = orchestrator(
                builder().keywordFastPathEnabled(true).build(), null);

        RetrievalOutcome outcome = orchestrator.retrieve(
                RetrievalQuery.of("tenant-1", List.of("proj-A"), "现金贷额度"));

        assertThat(outcome.trace().vectorUsed()).isFalse();
        assertThat(outcome.trace().degradedReason()).isEqualTo("KEYWORD_FAST_PATH");
    }

    // ==================== 辅助 ====================

    private RetrievalOrchestrator orchestrator(RagRuntimeSettings settings, ScoringService scorer) {
        return orchestratorWithIndex(settings, scorer, bm25IndexService);
    }

    @SuppressWarnings("unchecked")
    private RetrievalOrchestrator orchestratorWithIndex(RagRuntimeSettings settings, ScoringService scorer,
                                                        Bm25IndexService index) {
        when(ragConfigService.resolveSettings(anyString())).thenReturn(settings);
        ObjectProvider<ScoringService> provider = scorerProvider(scorer);
        RrfFusion fusion = new RrfFusion();
        return new RetrievalOrchestrator(
                ragConfigService,
                index,
                vectorRetriever,
                provider,
                fusion,
                new RerankGate(fusion),
                new ContextFilterChain(new EntityMatcher()),
                new ContextBlockAssembler(),
                cache,
                directExecutor,
                300,
                300);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ScoringService> scorerProvider(ScoringService scorer) {
        ObjectProvider<ScoringService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scorer);
        return provider;
    }

    private void indexChunk(String chunkId, String docId, String fileName, String text) {
        bm25IndexService.upsertChunks(List.of(new Bm25Chunk(chunkId, "tenant-1", "proj-A", docId,
                fileName, "正文", "document", text, text)));
    }

    private RagRuntimeSettings settings() {
        return builder().build();
    }

    private RagRuntimeSettings.RagRuntimeSettingsBuilder builder() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .collectionName("sf_context_items")
                .chunkSize(600)
                .chunkOverlap(100)
                .retrievalTopK(6)
                .retrievalMinScore(0.5)
                .embeddingDimension(1024)
                .bm25Enabled(true)
                .bm25TopK(20)
                .vectorTopK(20)
                .prefilterRequired(true)
                .rrfK(60)
                .fusionTopN(20)
                .rerankerEnabled(false)
                .rerankPolicy(RagDefaults.RERANK_POLICY_AUTO)
                .rerankerTopN(6)
                .rerankTimeoutMs(1500)
                .maxFilesPerContext(2)
                .entityOverlapFilterEnabled(true)
                .contextTokenBudget(4000)
                .keywordFastPathEnabled(false)
                .retrievalCacheEnabled(true)
                .retrievalCacheTtlSeconds(300)
                .evalRecallK(20);
    }

    /** 假向量召回 */
    private static final class StubVectorRetriever implements VectorCandidateRetriever {
        private List<RrfFusion.VectorHit> hits = new ArrayList<>();
        private boolean available = true;

        @Override
        public boolean isAvailable(String tenantId) {
            return available;
        }

        @Override
        public List<RrfFusion.VectorHit> retrieve(String tenantId, java.util.Collection<String> projectIds,
                                                  String query, int topK) {
            return hits.stream().limit(topK).toList();
        }
    }
}
