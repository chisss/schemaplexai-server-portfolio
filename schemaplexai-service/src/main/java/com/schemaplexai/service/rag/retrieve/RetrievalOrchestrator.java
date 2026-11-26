package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.model.entity.RagOperationLog;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.index.Bm25Hit;
import com.schemaplexai.service.rag.index.Bm25IndexService;
import com.schemaplexai.service.vector.EmbeddingTokenEstimator;
import com.schemaplexai.service.vector.ScoringService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 检索编排器（设计稿「层 4」）。
 *
 * <pre>
 * Query → 权限/范围 → 关键词快通道? → 并行[BM25 | 向量]（均带前置过滤）
 *       → RRF(k) → Rerank 门禁 → 精排(硬超时，超时回落)
 *       → 业务过滤(实体/文件数) → Token 硬上限 → 引用块装配 → 缓存
 * </pre>
 *
 * <p>降级是设计的一部分，不是意外：
 * <ul>
 *   <li>BM25 索引不可用 → 向量单路；</li>
 *   <li>向量库超时/不可用 → BM25 单路；</li>
 *   <li>精排超时/不可用 → 保留 RRF 原序；</li>
 *   <li>两路都失败 → 返回空结果 + 明确 degradedReason，由调用方决定是否走词法兜底。</li>
 * </ul>
 * 任何一路的失败都不会让整个问答挂掉。
 */
@Slf4j
@Service
public class RetrievalOrchestrator {

    private final RagConfigService ragConfigService;
    private final Bm25IndexService bm25IndexService;
    private final VectorCandidateRetriever vectorRetriever;
    private final ObjectProvider<ScoringService> scoringServiceProvider;
    private final RrfFusion rrfFusion;
    private final RerankGate rerankGate;
    private final ContextFilterChain contextFilterChain;
    private final ContextBlockAssembler contextBlockAssembler;
    private final RetrievalCache retrievalCache;
    private final Executor retrievalExecutor;
    private final long bm25TimeoutMs;
    private final long vectorTimeoutMs;

    public RetrievalOrchestrator(RagConfigService ragConfigService,
                                 Bm25IndexService bm25IndexService,
                                 VectorCandidateRetriever vectorRetriever,
                                 ObjectProvider<ScoringService> scoringServiceProvider,
                                 RrfFusion rrfFusion,
                                 RerankGate rerankGate,
                                 ContextFilterChain contextFilterChain,
                                 ContextBlockAssembler contextBlockAssembler,
                                 RetrievalCache retrievalCache,
                                 @Qualifier("ragRetrievalExecutor") Executor retrievalExecutor,
                                 @Value("${rag.retrieval.bm25-timeout-ms:800}") long bm25TimeoutMs,
                                 @Value("${rag.retrieval.vector-timeout-ms:1200}") long vectorTimeoutMs) {
        this.ragConfigService = ragConfigService;
        this.bm25IndexService = bm25IndexService;
        this.vectorRetriever = vectorRetriever;
        this.scoringServiceProvider = scoringServiceProvider;
        this.rrfFusion = rrfFusion;
        this.rerankGate = rerankGate;
        this.contextFilterChain = contextFilterChain;
        this.contextBlockAssembler = contextBlockAssembler;
        this.retrievalCache = retrievalCache;
        this.retrievalExecutor = retrievalExecutor;
        this.bm25TimeoutMs = bm25TimeoutMs;
        this.vectorTimeoutMs = vectorTimeoutMs;
    }

    public RetrievalOutcome retrieve(RetrievalQuery query) {
        return retrieve(query, false, null);
    }

    /**
     * @param budgetPressure 租户 token 预算是否已告警（由调用方/预算服务传入，用于 Rerank 门禁）
     */
    public RetrievalOutcome retrieve(RetrievalQuery query, boolean budgetPressure) {
        return retrieve(query, budgetPressure, null);
    }

    /**
     * 指定运行时设置执行检索（评测/A-B 用）。
     *
     * <p>评测必须能覆盖租户配置，否则只能"改配置再跑两次"，既影响线上又会污染缓存。
     * 调用方应同时把 {@code retrievalCacheEnabled} 关掉，避免两次实验互相命中。
     */
    public RetrievalOutcome retrieve(RetrievalQuery query, boolean budgetPressure, RagRuntimeSettings override) {
        long startMs = System.currentTimeMillis();
        if (query == null || !StringUtils.hasText(query.tenantId()) || !StringUtils.hasText(query.queryText())) {
            return RetrievalOutcome.empty(RetrievalTrace.builder()
                    .degradedReason("INVALID_QUERY").totalLatencyMs(0).build());
        }

        RagRuntimeSettings settings = override != null
                ? override
                : ragConfigService.resolveSettings(query.tenantId());
        if (!settings.isEnabled()) {
            return RetrievalOutcome.empty(RetrievalTrace.builder()
                    .degradedReason("RAG_DISABLED")
                    .totalLatencyMs(System.currentTimeMillis() - startMs).build());
        }

        int topK = query.topK() != null && query.topK() > 0 ? query.topK() : settings.getRetrievalTopK();
        List<String> projectIds = query.projectList();
        String cacheKey = RetrievalCache.buildKey(query.tenantId(), projectIds, query.queryText(),
                settings.retrievalVersion(), topK);

        if (settings.isRetrievalCacheEnabled()) {
            RetrievalOutcome cached = retrievalCache.get(cacheKey);
            if (cached != null) {
                long elapsed = System.currentTimeMillis() - startMs;
                return new RetrievalOutcome(cached.chunks(), cached.contextBlock(),
                        RetrievalTrace.cached(cached.trace(), elapsed));
            }
        }

        AtomicReference<String> degraded = new AtomicReference<>();
        boolean keywordOnly = settings.isKeywordFastPathEnabled() && isKeywordQuery(query.queryText());

        // ── 1. 并行粗召回（都带前置过滤） ──
        boolean bm25Available = settings.isBm25Enabled() && bm25IndexService.isAvailable();
        boolean vectorAvailable = !keywordOnly && vectorRetriever.isAvailable(query.tenantId());

        CompletableFuture<List<Bm25Hit>> bm25Future = bm25Available
                ? CompletableFuture.supplyAsync(() -> safeBm25(query, settings), retrievalExecutor)
                : CompletableFuture.completedFuture(List.of());
        CompletableFuture<List<RrfFusion.VectorHit>> vectorFuture = vectorAvailable
                ? CompletableFuture.supplyAsync(() -> safeVector(query, settings), retrievalExecutor)
                : CompletableFuture.completedFuture(List.of());

        long bm25Start = System.currentTimeMillis();
        List<Bm25Hit> bm25Hits = await(bm25Future, bm25TimeoutMs, "BM25", degraded);
        long bm25Latency = System.currentTimeMillis() - bm25Start;
        long vectorStart = System.currentTimeMillis();
        List<RrfFusion.VectorHit> vectorHits = await(vectorFuture, vectorTimeoutMs, "VECTOR", degraded);
        long vectorLatency = System.currentTimeMillis() - vectorStart;

        if (keywordOnly) {
            degraded.compareAndSet(null, "KEYWORD_FAST_PATH");
        }

        // ── 2. RRF 融合 ──
        int fusionTopN = Math.max(topK, settings.getFusionTopN());
        List<RetrievalCandidate> fused = rrfFusion.fuse(bm25Hits, vectorHits, settings.getRrfK(), fusionTopN);
        if (fused.isEmpty()) {
            RetrievalTrace trace = RetrievalTrace.builder()
                    .bm25Used(bm25Available).vectorUsed(vectorAvailable)
                    .bm25Hits(bm25Hits.size()).vectorHits(vectorHits.size()).fusedCount(0)
                    .bm25LatencyMs(bm25Latency).vectorLatencyMs(vectorLatency)
                    .totalLatencyMs(System.currentTimeMillis() - startMs)
                    .degradedReason(degraded.get() == null ? "NO_HITS" : degraded.get())
                    .build();
            recordOperation(query, settings, 0, trace);
            return RetrievalOutcome.empty(trace);
        }

        // ── 3. 是否精排 ──
        ScoringService scorer = scoringServiceProvider.getIfAvailable();
        boolean scorerAvailable = scorer != null && scorer.isAvailable();
        boolean upstreamTimeout = degraded.get() != null
                && (degraded.get().contains("TIMEOUT") || degraded.get().contains("FAILED"));
        RerankGate.RerankDecision decision = rerankGate.decide(settings, query, fused,
                new RerankGate.RerankSignals(scorerAvailable, budgetPressure, upstreamTimeout));

        List<RetrievalCandidate> ranked = fused;
        boolean rerankApplied = false;
        boolean rerankDegraded = false;
        long rerankLatency = 0L;
        if (decision.rerank() && scorer != null) {
            long rerankStart = System.currentTimeMillis();
            try {
                ranked = rerankWithTimeout(scorer, query.queryText(), fused, settings);
                rerankApplied = true;
            } catch (Exception e) {
                rerankDegraded = true;
                degraded.compareAndSet(null, "RERANK_" + (e instanceof java.util.concurrent.TimeoutException
                        ? "TIMEOUT" : "FAILED"));
                log.warn("精排失败，回落 RRF 原序: tenantId={}, reason={}", query.tenantId(), e.getMessage());
            } finally {
                rerankLatency = System.currentTimeMillis() - rerankStart;
            }
        }

        // ── 4. 业务过滤 + Token 硬上限 ──
        ContextFilterChain.FilterOutcome filtered = contextFilterChain.apply(ranked, query, settings);
        List<RetrievalCandidate> finalChunks = filtered.kept().stream().limit(topK).toList();
        String contextBlock = contextBlockAssembler.assemble(finalChunks);

        RetrievalTrace trace = RetrievalTrace.builder()
                .bm25Used(bm25Available)
                .vectorUsed(vectorAvailable)
                .bm25Hits(bm25Hits.size())
                .vectorHits(vectorHits.size())
                .fusedCount(fused.size())
                .rerankApplied(rerankApplied)
                .rerankReason(decision.reason())
                .rerankDegraded(rerankDegraded)
                .keptCount(finalChunks.size())
                .droppedByEntity(filtered.droppedByEntity())
                .droppedByFileLimit(filtered.droppedByFileLimit())
                .droppedByTokenBudget(filtered.droppedByTokenBudget())
                .contextTokens(filtered.totalTokens())
                .bm25LatencyMs(bm25Latency)
                .vectorLatencyMs(vectorLatency)
                .rerankLatencyMs(rerankLatency)
                .totalLatencyMs(System.currentTimeMillis() - startMs)
                .cacheHit(false)
                .degradedReason(degraded.get())
                .rerankModel(settings.isBuiltinReranker() ? "onnx-cross-encoder" : settings.getRerankerSource())
                .build();

        RetrievalOutcome outcome = new RetrievalOutcome(finalChunks, contextBlock, trace);
        if (settings.isRetrievalCacheEnabled()) {
            retrievalCache.put(cacheKey, outcome, settings.getRetrievalCacheTtlSeconds());
        }
        recordOperation(query, settings, finalChunks.size(), trace);
        return outcome;
    }

    /**
     * 简单关键词查询走 BM25 快通道，跳过 Embedding。
     * 判据保守：短、无疑问词、无多子句。宁可多花一次 embedding，也不要错杀语义查询。
     */
    boolean isKeywordQuery(String queryText) {
        if (!StringUtils.hasText(queryText)) {
            return false;
        }
        String trimmed = queryText.trim();
        if (trimmed.length() > 12) {
            return false;
        }
        return !trimmed.matches(".*(为什么|如何|怎么|是否|对比|区别|原因|影响|\\?|？).*");
    }

    private List<Bm25Hit> safeBm25(RetrievalQuery query, RagRuntimeSettings settings) {
        try {
            return bm25IndexService.search(query.queryText(), query.tenantId(), query.projectList(),
                    Math.max(1, settings.getBm25TopK()));
        } catch (Exception e) {
            log.warn("BM25 召回异常: tenantId={}, error={}", query.tenantId(), e.getMessage());
            return List.of();
        }
    }

    private List<RrfFusion.VectorHit> safeVector(RetrievalQuery query, RagRuntimeSettings settings) {
        try {
            return vectorRetriever.retrieve(query.tenantId(), query.projectList(), query.queryText(),
                    Math.max(1, settings.getVectorTopK()));
        } catch (Exception e) {
            log.warn("向量召回异常: tenantId={}, error={}", query.tenantId(), e.getMessage());
            return List.of();
        }
    }

    /** 每路独立硬超时：一路慢不能拖死整条检索 */
    private <T> List<T> await(CompletableFuture<List<T>> future, long timeoutMs, String path,
                              AtomicReference<String> degraded) {
        try {
            List<T> result = future.get(Math.max(50, timeoutMs), TimeUnit.MILLISECONDS);
            return result == null ? List.of() : result;
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            degraded.compareAndSet(null, path + "_TIMEOUT");
            log.warn("{} 召回超时({}ms)，本次降级", path, timeoutMs);
            return List.of();
        } catch (Exception e) {
            degraded.compareAndSet(null, path + "_FAILED");
            log.warn("{} 召回失败: {}", path, e.getMessage());
            return List.of();
        }
    }

    private List<RetrievalCandidate> rerankWithTimeout(ScoringService scorer, String queryText,
                                                       List<RetrievalCandidate> fused,
                                                       RagRuntimeSettings settings) throws Exception {
        List<String> passages = fused.stream().map(RetrievalCandidate::text).toList();
        CompletableFuture<List<ScoringService.ScoredPassage>> future = CompletableFuture.supplyAsync(
                () -> scorer.rerank(queryText, passages, Math.max(1, settings.getRerankerTopN())),
                retrievalExecutor);

        List<ScoringService.ScoredPassage> scored;
        try {
            scored = future.get(Math.max(100, settings.getRerankTimeoutMs()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw e;
        }
        if (scored == null || scored.isEmpty()) {
            return fused;
        }

        Map<String, Double> scoreByText = new LinkedHashMap<>();
        scored.forEach(passage -> scoreByText.merge(passage.text(), passage.score(), Math::max));

        List<RetrievalCandidate> reranked = new ArrayList<>(fused.size());
        for (RetrievalCandidate candidate : fused) {
            Double score = scoreByText.get(candidate.text());
            reranked.add(score == null ? candidate : candidate.withRerankScore(score));
        }
        reranked.sort(java.util.Comparator.comparingDouble(RetrievalCandidate::effectiveScore).reversed());
        return reranked;
    }

    private void recordOperation(RetrievalQuery query, RagRuntimeSettings settings, int retrievedCount,
                                 RetrievalTrace trace) {
        try {
            RagOperationLog operationLog = new RagOperationLog();
            operationLog.setTenantId(query.tenantId());
            operationLog.setOperationType("query");
            operationLog.setSourceType("rag_v2_retrieval");
            operationLog.setCollectionName(settings.getCollectionName());
            operationLog.setModelConfigId(settings.getVectorModelConfigId());
            operationLog.setModelName(settings.getVectorModelName());
            operationLog.setProvider(settings.getProvider());
            operationLog.setVectorDimension(settings.getEmbeddingDimension());
            operationLog.setRequestChars(query.queryText() == null ? 0 : query.queryText().length());
            operationLog.setRequestTokens(EmbeddingTokenEstimator.estimate(query.queryText()));
            operationLog.setRetrievedCount(retrievedCount);
            operationLog.setDurationMs(trace.totalLatencyMs());
            operationLog.setStatus(retrievedCount > 0 ? "success" : "empty");

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("bm25Hits", trace.bm25Hits());
            metadata.put("vectorHits", trace.vectorHits());
            metadata.put("fusedCount", trace.fusedCount());
            metadata.put("rerankApplied", trace.rerankApplied());
            metadata.put("rerankReason", trace.rerankReason());
            metadata.put("rerankDegraded", trace.rerankDegraded());
            metadata.put("droppedByEntity", trace.droppedByEntity());
            metadata.put("droppedByFileLimit", trace.droppedByFileLimit());
            metadata.put("droppedByTokenBudget", trace.droppedByTokenBudget());
            metadata.put("contextTokens", trace.contextTokens());
            metadata.put("cacheHit", trace.cacheHit());
            if (trace.degradedReason() != null) {
                metadata.put("degradedReason", trace.degradedReason());
            }
            operationLog.setMetadata(metadata);
            ragConfigService.recordOperation(operationLog);
        } catch (Exception e) {
            log.debug("记录 RAG 检索日志失败: {}", e.getMessage());
        }
    }
}
