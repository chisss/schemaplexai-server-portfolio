package com.schemaplexai.service.rag.retrieve;

import lombok.Builder;

/**
 * 检索链路埋点。
 *
 * <p>字段设计的出发点是"线上出问题能一眼定位"：
 * 延迟高在哪一段、精排有没有开/为什么没开、片段是在哪一闸被砍掉的。
 */
@Builder
public record RetrievalTrace(boolean bm25Used,
                             boolean vectorUsed,
                             int bm25Hits,
                             int vectorHits,
                             int fusedCount,
                             boolean rerankApplied,
                             String rerankReason,
                             boolean rerankDegraded,
                             int keptCount,
                             int droppedByEntity,
                             int droppedByFileLimit,
                             int droppedByTokenBudget,
                             int contextTokens,
                             long bm25LatencyMs,
                             long vectorLatencyMs,
                             long rerankLatencyMs,
                             long totalLatencyMs,
                             boolean cacheHit,
                             String degradedReason,
                             String rerankModel) {

    public static RetrievalTrace cached(RetrievalTrace origin, long totalLatencyMs) {
        return RetrievalTrace.builder()
                .bm25Used(origin.bm25Used)
                .vectorUsed(origin.vectorUsed)
                .bm25Hits(origin.bm25Hits)
                .vectorHits(origin.vectorHits)
                .fusedCount(origin.fusedCount)
                .rerankApplied(origin.rerankApplied)
                .rerankReason(origin.rerankReason)
                .rerankDegraded(origin.rerankDegraded)
                .keptCount(origin.keptCount)
                .droppedByEntity(origin.droppedByEntity)
                .droppedByFileLimit(origin.droppedByFileLimit)
                .droppedByTokenBudget(origin.droppedByTokenBudget)
                .contextTokens(origin.contextTokens)
                .bm25LatencyMs(origin.bm25LatencyMs)
                .vectorLatencyMs(origin.vectorLatencyMs)
                .rerankLatencyMs(origin.rerankLatencyMs)
                .totalLatencyMs(totalLatencyMs)
                .cacheHit(true)
                .degradedReason(origin.degradedReason)
                .rerankModel(origin.rerankModel)
                .build();
    }
}
