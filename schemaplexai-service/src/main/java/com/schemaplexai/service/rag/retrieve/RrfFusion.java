package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.index.Bm25Hit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）倒数排名融合。
 *
 * <p>公式：{@code RRF(d) = Σ 1 / (k + rank_i(d))}，k 默认 60。
 *
 * <p>为什么不用分数加权：BM25 分数无上界，余弦相似度在 [-1,1]，两者量纲不可比，
 * 归一化又会被单次查询的分布带偏。RRF 只用排名，天然规避这个问题，
 * 这也是它在混合检索里几乎总是"先上"的基线方案。
 *
 * <p>纯内存计算，无模型开销，可以放心放在每次检索的主链路上。
 */
@Component
public class RrfFusion {

    /** 设计稿默认 k=60 */
    public static final int DEFAULT_K = 60;

    /**
     * 融合多路召回。
     *
     * @param bm25Hits    BM25 路召回（已按排名排序）
     * @param vectorHits  向量路召回（已按排名排序）
     * @param k           平滑常数
     * @param limit       融合后保留的候选数
     */
    public List<RetrievalCandidate> fuse(List<Bm25Hit> bm25Hits, List<VectorHit> vectorHits, int k, int limit) {
        int smooth = k > 0 ? k : DEFAULT_K;
        Map<String, CandidateAccumulator> accumulators = new LinkedHashMap<>();

        if (bm25Hits != null) {
            for (int i = 0; i < bm25Hits.size(); i++) {
                Bm25Hit hit = bm25Hits.get(i);
                CandidateAccumulator acc = accumulators.computeIfAbsent(hit.chunkId(),
                        id -> new CandidateAccumulator(id, hit.docId(), hit.fileName(), hit.section(),
                                hit.category(), hit.text()));
                acc.bm25Rank = i + 1;
                acc.bm25Score = (double) hit.score();
                acc.rrfScore += 1.0D / (smooth + acc.bm25Rank);
            }
        }

        if (vectorHits != null) {
            for (int i = 0; i < vectorHits.size(); i++) {
                VectorHit hit = vectorHits.get(i);
                CandidateAccumulator acc = accumulators.computeIfAbsent(hit.chunkId(),
                        id -> new CandidateAccumulator(id, hit.docId(), hit.fileName(), hit.section(),
                                hit.category(), hit.text()));
                acc.vectorRank = i + 1;
                acc.vectorScore = hit.score();
                acc.rrfScore += 1.0D / (smooth + acc.vectorRank);
            }
        }

        List<RetrievalCandidate> fused = new ArrayList<>(accumulators.size());
        for (CandidateAccumulator acc : accumulators.values()) {
            fused.add(new RetrievalCandidate(acc.chunkId, acc.docId, acc.fileName, acc.section, acc.category,
                    acc.text, acc.bm25Rank, acc.vectorRank, acc.bm25Score, acc.vectorScore,
                    acc.rrfScore, null, acc.matchedBy()));
        }
        fused.sort(Comparator.comparingDouble(RetrievalCandidate::rrfScore).reversed());

        int size = limit > 0 ? Math.min(limit, fused.size()) : fused.size();
        return List.copyOf(fused.subList(0, size));
    }

    /**
     * 分数分布是否"扁平"：前几名分差很小，说明两路对谁更相关没有共识，值得上精排。
     *
     * <p>阈值取 15%：RRF 分数量级很小（~1/61），用相对差而不是绝对值判断。
     */
    public boolean isFlatDistribution(List<RetrievalCandidate> candidates, double relativeGapThreshold) {
        if (candidates == null || candidates.size() < 3) {
            return false;
        }
        double top = candidates.get(0).rrfScore();
        int compareIndex = Math.min(4, candidates.size() - 1);
        double fifth = candidates.get(compareIndex).rrfScore();
        if (top <= 0) {
            return false;
        }
        double relativeGap = (top - fifth) / top;
        return relativeGap < relativeGapThreshold;
    }

    /** 向量路命中（与 Bm25Hit 同形，避免检索侧依赖向量库实现类） */
    public record VectorHit(String chunkId,
                            String docId,
                            String fileName,
                            String section,
                            String category,
                            String text,
                            double score) {
    }

    private static final class CandidateAccumulator {
        private final String chunkId;
        private final String docId;
        private final String fileName;
        private final String section;
        private final String category;
        private final String text;
        private Integer bm25Rank;
        private Integer vectorRank;
        private Double bm25Score;
        private Double vectorScore;
        private double rrfScore;

        private CandidateAccumulator(String chunkId, String docId, String fileName, String section,
                                     String category, String text) {
            this.chunkId = chunkId;
            this.docId = docId;
            this.fileName = fileName;
            this.section = section;
            this.category = category;
            this.text = text;
        }

        private String matchedBy() {
            if (bm25Rank != null && vectorRank != null) {
                return "bm25+vector";
            }
            return bm25Rank != null ? "bm25" : "vector";
        }
    }
}
